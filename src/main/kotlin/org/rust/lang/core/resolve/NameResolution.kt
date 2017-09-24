/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

@file:Suppress("LoopToCallChain")

package org.rust.lang.core.resolve

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.*
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.cargo.util.getPsiFor
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.ref.RsReference
import org.rust.lang.core.stubs.index.RsNamedElementIndex
import org.rust.lang.core.types.BoundElement
import org.rust.lang.core.types.infer.substitute
import org.rust.lang.core.types.ty.*
import org.rust.lang.core.types.type
import java.util.*

// IntelliJ Rust name resolution algorithm.
// Collapse all methods (`ctrl shift -`) to get a bird's eye view.
//
// The entry point is
// `process~X~ResolveVariants(x: RsReferenceElement, processor: RsResolveProcessor)`
// family of methods.
//
// Conceptually, each of these methods returns a sequence of `RsNameElement`s
// visible at the reference location. During completion, all of them are presented
// as completion variants, and during resolve only the one with the name matching
// the reference is selected.
//
// Instead of Kotlin `Sequence`'s, a callback (`RsResolveProcessor`) is used, because
// it gives **much** nicer stacktraces (we used to have `Sequence` here some time ago).
//
// Instead of using `RsNameElement` directly, `RsResolveProcessor` operates on `ScopeEntry`s.
// `ScopeEntry` allows to change the effective name of an element (for aliases) and to retrieve
// the actual element lazily.
//
// The `process~PsiElement~Declarations` family of methods list name elements belonging
// to a particular element (for example, variants of an enum).
//
// Technicalities:
//
//   * We can get into infinite loop during name resolution. This is handled by
//     `RsReferenceBase`.
//   * The results of name resolution are cached and invalidated on every code change.
//     Caching also is handled by `RsReferenceBase`.
//   * Ideally, all of the methods except for `processLexicalDeclarations` should operate on stubs only.
//   * Rust uses two namespaces for declarations ("types" and "values"). The necessary namespace is
//     determined by the syntactic position of the reference in `processResolveVariants` function and
//     is passed down to the `processDeclarations` functions.
//   * Instead of `getParent` we use `getContext` here. This trick allows for funny things like creating
//     a code fragment in a temporary file and attaching it to some existing file. See the usages of
//     [RsCodeFragmentFactory]

fun processFieldExprResolveVariants(
    lookup: ImplLookup,
    receiverType: Ty,
    isCompletion: Boolean,
    processor: RsResolveProcessor
): Boolean {
    for (ty in lookup.coercionSequence(receiverType)) {
        if (ty !is TyStruct) continue
        if (processFieldDeclarations(ty.item, ty.typeParameterValues, processor)) return true
    }
    if (isCompletion && processMethodDeclarationsWithDeref(lookup, receiverType, processor)) {
        return true
    }
    return false
}

fun processStructLiteralFieldResolveVariants(field: RsStructLiteralField, processor: RsResolveProcessor): Boolean {
    val structOrEnumVariant = field.parentStructLiteral.path.reference.resolve() as? RsFieldsOwner ?: return false
    return processFieldDeclarations(structOrEnumVariant, emptySubstitution, processor)
}

fun processMethodCallExprResolveVariants(lookup: ImplLookup, receiverType: Ty, processor: RsResolveProcessor): Boolean {
    return processMethodDeclarationsWithDeref(lookup, receiverType, processor)
}

fun processUseGlobResolveVariants(glob: RsUseGlob, processor: RsResolveProcessor): Boolean {
    val useItem = glob.parentUseItem
    val basePath = useItem.path
    val baseItem = (if (basePath != null)
        basePath.reference.resolve()
    else
    // `use ::{foo, bar}`
        glob.crateRoot) ?: return false

    if (processor("self", baseItem)) return true

    return processItemOrEnumVariantDeclarations(baseItem, TYPES_N_VALUES, processor,
        withPrivateImports = basePath != null && isSuperChain(basePath)
    )
}

/**
 * Looks-up file corresponding to particular module designated by `mod-declaration-item`:
 *
 *  ```
 *  // foo.rs
 *  pub mod bar; // looks up `bar.rs` or `bar/mod.rs` in the same dir
 *
 *  pub mod nested {
 *      pub mod baz; // looks up `nested/baz.rs` or `nested/baz/mod.rs`
 *  }
 *
 *  ```
 *
 *  | A module without a body is loaded from an external file, by default with the same name as the module,
 *  | plus the '.rs' extension. When a nested sub-module is loaded from an external file, it is loaded
 *  | from a subdirectory path that mirrors the module hierarchy.
 *
 * Reference:
 *      https://github.com/rust-lang/rust/blob/master/src/doc/reference.md#modules
 */
fun processModDeclResolveVariants(modDecl: RsModDeclItem, processor: RsResolveProcessor): Boolean {
    val dir = modDecl.containingMod.ownedDirectory ?: return false

    val explicitPath = modDecl.pathAttribute
    if (explicitPath != null) {
        val vFile = dir.virtualFile.findFileByRelativePath(explicitPath) ?: return false
        val mod = PsiManager.getInstance(modDecl.project).findFile(vFile)?.rustMod ?: return false

        val name = modDecl.name ?: return false
        return processor(name, mod)
    }
    if (modDecl.isLocal) return false

    for (file in dir.files) {
        if (file == modDecl.containingFile.originalFile || file.name == RsMod.MOD_RS) continue
        val mod = file.rustMod ?: continue
        val fileName = FileUtil.getNameWithoutExtension(file.name)
        val modDeclName = modDecl.referenceName
        // Handle case-insensitive filesystem (windows)
        val name = if (modDeclName.toLowerCase() == fileName.toLowerCase()) {
            modDeclName
        } else {
            fileName
        }
        if (processor(name, mod)) return true
    }

    for (d in dir.subdirectories) {
        val mod = d.findFile(RsMod.MOD_RS)?.rustMod ?: continue
        if (processor(d.name, mod)) return true
    }

    return false
}

fun processExternCrateResolveVariants(crate: RsExternCrateItem, isCompletion: Boolean, processor: RsResolveProcessor): Boolean {
    val pkg = crate.containingCargoPackage ?: return false

    val visitedDeps = mutableSetOf<String>()
    fun processPackage(pkg: CargoWorkspace.Package): Boolean {
        if (isCompletion && pkg.origin != PackageOrigin.DEPENDENCY) return false
        val libTarget = pkg.libTarget ?: return false

        if (pkg.origin == PackageOrigin.STDLIB && pkg.name in visitedDeps) return false
        visitedDeps += pkg.name
        return processor.lazy(libTarget.normName) {
            crate.project.getPsiFor(libTarget.crateRoot)?.rustMod
        }
    }

    if (processPackage(pkg)) return true
    val explicitDepsFirst = pkg.dependencies.sortedBy {
        when (it.origin) {
            PackageOrigin.WORKSPACE -> 0
            PackageOrigin.DEPENDENCY -> 1
            PackageOrigin.STDLIB -> 2
            PackageOrigin.TRANSITIVE_DEPENDENCY -> 3
        }
    }
    for (p in explicitDepsFirst) {
        if (processPackage(p)) return true
    }
    return false
}

fun processPathResolveVariants(lookup: ImplLookup, path: RsPath, isCompletion: Boolean, processor: RsResolveProcessor): Boolean {
    val qualifier = path.path
    val typeQual = path.typeQual
    val parent = path.context
    val ns = when (parent) {
        is RsPath, is RsTypeElement, is RsTraitRef, is RsStructLiteral -> TYPES
        is RsUseItem -> if (parent.isStarImport) TYPES else TYPES_N_VALUES
        is RsPathExpr -> if (isCompletion) TYPES_N_VALUES else VALUES
        else -> TYPES_N_VALUES
    }

    if (qualifier != null) {
        val primitiveType = TyPrimitive.fromPath(qualifier)
        if (primitiveType != null) {
            if (processAssociatedItems(lookup, primitiveType, ns, processor)) return true
        }

        val (base, subst) = qualifier.reference.advancedResolve() ?: return false
        if (base is RsMod) {
            val s = base.`super`
            if (s != null && processor("super", s)) return true
        }
        if (processItemOrEnumVariantDeclarations(base, ns, processor, isSuperChain(qualifier))) return true
        if (base is RsTypeDeclarationElement && parent !is RsUseItem) {
            if (processAssociatedItems(lookup, base.declaredType.substitute(subst), ns, processor)) return true
        }
        return false
    }

    if (typeQual != null) {
        val trait = typeQual.traitRef?.resolveToBoundTrait ?: return false
        val subst = trait.subst.substituteInValues(mapOf(TyTypeParameter.self() to typeQual.typeReference.type))
        if (processAllWithSubst(trait.element.members?.typeAliasList.orEmpty(), subst, processor)) return true
    }

    val containingMod = path.containingMod
    val crateRoot = path.crateRoot
    if (!path.hasColonColon) {
        if (Namespace.Types in ns) {
            if (processor("self", containingMod)) return true
            val superMod = containingMod.`super`
            if (superMod != null && processor("super", superMod)) return true
        }
    }

    // Paths in use items are implicitly global.
    if (path.hasColonColon || path.contextOfType<RsUseItem>() != null) {
        if (crateRoot != null) {
            if (processItemOrEnumVariantDeclarations(crateRoot, ns, processor)) return true
        }
        return false
    }

    return processNestedScopesUpwards(path, processor, ns)
}

fun processPatBindingResolveVariants(binding: RsPatBinding, isCompletion: Boolean, processor: RsResolveProcessor): Boolean {
    return processNestedScopesUpwards(binding, { entry ->
        processor.lazy(entry.name) {
            val element = entry.element
            val isConstant = element is RsConstant
                || (element is RsEnumVariant && element.blockFields == null && element.tupleFields == null)
            val isPathOrDestructable = when (element) {
                is RsMod, is RsEnumItem, is RsEnumVariant, is RsStructItem -> true
                else -> false
            }
            if (isConstant || (isCompletion && isPathOrDestructable)) element else null
        }
    }, if (isCompletion) TYPES_N_VALUES else VALUES)
}

fun processLabelResolveVariants(label: RsLabel, processor: RsResolveProcessor): Boolean {
    for (scope in label.ancestors) {
        if (scope is RsLambdaExpr || scope is RsFunction) return false
        if (scope is RsLabeledExpression) {
            val lableDecl = scope.labelDecl ?: continue
            if (processor(lableDecl)) return true
        }
    }
    return false
}

fun processLifetimeResolveVariants(lifetime: RsLifetime, processor: RsResolveProcessor): Boolean {
    if (lifetime.isPredefined) return false
    loop@ for (scope in lifetime.ancestors) {
        val lifetimeParameters = when (scope) {
            is RsGenericDeclaration -> scope.typeParameterList?.lifetimeParameterList
            is RsWhereClause -> scope.wherePredList.mapNotNull { it.forLifetimes }.flatMap { it.lifetimeParameterList }
            is RsForInType -> scope.forLifetimes.lifetimeParameterList
            is RsPolybound -> scope.forLifetimes?.lifetimeParameterList
            else -> continue@loop
        }
        if (processAll(lifetimeParameters.orEmpty(), processor)) return true
    }
    return false
}

fun processLocalVariables(place: RsCompositeElement, processor: (RsPatBinding) -> Unit) {
    walkUp(place, { it is RsItemElement }) { cameFrom, scope ->
        processLexicalDeclarations(scope, cameFrom, VALUES) { v ->
            val el = v.element
            if (el is RsPatBinding) processor(el)
            true
        }
    }
}

/**
 * Resolves an absolute path.
 */
fun resolveStringPath(path: String, workspace: CargoWorkspace, project: Project): Pair<RsNamedElement, CargoWorkspace.Package>? {
    val parts = path.split("::", limit = 2)
    if (parts.size != 2) return null
    val pkg = workspace.findPackage(parts[0]) ?: return null

    val el = pkg.targets.asSequence()
        .mapNotNull { RsCodeFragmentFactory(project).createCrateRelativePath("::${parts[1]}", it) }
        .mapNotNull { it.reference.resolve() }
        .filterIsInstance<RsNamedElement>()
        .firstOrNull() ?: return null
    return el to pkg
}

fun processMacroReferenceVariants(ref: RsMacroReference, processor: RsResolveProcessor): Boolean {
    val definition = ref.parentOfType<RsMacroDefinitionCase>() ?: return false
    val simple = definition.macroPattern.descendantsOfType<RsMacroBinding>()
        .toList()

    return simple.any { processor(it) }
}

fun processMetaItemResolveVariants(element: RsMetaItem, processor: RsResolveProcessor): Boolean {
    if (element.parentOfType<RsMetaItem>()?.referenceName != "derive") return false
    val traitName = element.referenceName
    val traits = RsNamedElementIndex.findDerivableTraits(element.project, traitName)
    return processAll(traits, processor)
}

fun processMacroCallVariants(invocation: PsiElement, processor: RsResolveProcessor): Boolean =
    walkUp(invocation, { false }, false) { _, scope -> processMacroDeclarations(scope, processor) }

fun processMacroDeclarations(scope: RsCompositeElement, processor: RsResolveProcessor): Boolean {
    when (scope) {
        is RsFile -> if (processItemMacroDeclarations(scope, processor, scope.isCrateRoot)) return true
        is RsMod -> if (processItemMacroDeclarations(scope, processor)) return true
        is RsBlock -> if (processItemMacroDeclarations(scope, processor)) return true
    }
    return false
}

val RsFile.exportedCrateMacros: List<RsMacroDefinition>
    get() = CachedValuesManager.getCachedValue(this) {
        val macros = exportedCrateMacros(this, true)
        CachedValueProvider.Result.create(macros, PsiModificationTracker.MODIFICATION_COUNT)
    }

private fun exportedCrateMacros(scope: RsItemsOwner, needExport: Boolean): List<RsMacroDefinition> {
    val macros: MutableList<RsMacroDefinition> = scope.macroDefinitionList
        .filter { !needExport || it.hasMacroExport }
        .toMutableList()

    if (needExport) {
        for (crate in scope.externCrateItemList) {
            val reexport = crate.findOuterAttr("macro_reexport")
                ?.metaItem?.metaItemArgs?.metaItemList
                ?.mapNotNull { it.referenceName } ?: continue
            val mod = crate.reference.resolve() as? RsFile ?: continue
            val internalMacros = mod.exportedCrateMacros

            macros.addAll(internalMacros.filter { reexport.contains(it.name) })
        }
    } else {
        for (crate in scope.externCrateItemList.filter { it.hasMacroUse }) {
            val mod = crate.reference.resolve() as? RsFile ?: continue
            macros.addAll(mod.exportedCrateMacros)
        }
    }

    for (modDecl in scope.modDeclItemList.filter { it.hasMacroUse }) {
        val mod = modDecl.reference.resolve() ?: continue
        if (mod is RsMod) {
            macros.addAll(exportedCrateMacros(mod, true))
        }
    }

    for (mod in scope.modItemList.filter { it.hasMacroUse }) {
        macros.addAll(exportedCrateMacros(mod, true))
    }
    return macros
}

private fun processItemMacroDeclarations(
    scope: RsItemsOwner,
    processor: RsResolveProcessor,
    addStdCrate: Boolean = false
): Boolean {
    val macros = exportedCrateMacros(scope, false)
    if (processAll(macros, processor)) return true

    if (addStdCrate) {
        val stdCrate = scope.containingCargoPackage?.findDependency("std")?.crateRoot
        val prelude = scope.project.getPsiFor(stdCrate)?.rustMod
        if (prelude is RsFile && processAll(prelude.exportedCrateMacros, processor)) return true
    }
    return false
}

private fun processFieldDeclarations(struct: RsFieldsOwner, subst: Substitution, processor: RsResolveProcessor): Boolean {
    if (processAllBound(struct.namedFields, subst, processor)) return true

    for ((idx, field) in struct.positionalFields.withIndex()) {
        if (processor(idx.toString(), field, subst)) return true
    }
    return false
}

private fun processMethodDeclarationsWithDeref(lookup: ImplLookup, receiver: Ty, processor: RsResolveProcessor): Boolean {
    val methodProcessor: (ScopeEntry) -> Boolean = { entry ->
        val element = entry.element
        element is RsFunction && !element.isAssocFn && processor(entry)
    }
    return lookup.coercionSequence(receiver).any { processAssociatedItems(lookup, it, VALUES, methodProcessor) }
}

private fun processAssociatedItems(lookup: ImplLookup, type: Ty, ns: Set<Namespace>, processor: RsResolveProcessor): Boolean {
    val visitedInherent = mutableSetOf<String>()
    fun processTraitOrImpl(traitOrImpl: BoundElement<RsTraitOrImpl>, inherent: Boolean): Boolean {
        fun inherentProcessor(entry: BoundElement<RsNamedElement>): Boolean {
            val name = entry.element.name ?: return false
            if (inherent) visitedInherent.add(name)
            if (!inherent && name in visitedInherent) return false
            return processor(entry)
        }

        /**
         * For `impl T for Foo`, this'll walk impl members and trait `T` members,
         * which are not implemented.
         */
        fun processMembersWithDefaults(accessor: (RsMembers) -> List<RsNamedElement>): Boolean {
            val (element, subst) = traitOrImpl
            val directlyImplemented = element.members?.let { accessor(it) }.orEmpty().map { BoundElement(it, subst) }
            if (directlyImplemented.any { inherentProcessor(it) }) return true

            if (element is RsImplItem) {
                val direct = directlyImplemented.map { it.element.name }.toSet()
                val membersFromTrait = element.implementedTrait?.element?.members ?: return false
                for (member in accessor(membersFromTrait)) {
                    if (member.name !in direct && inherentProcessor(BoundElement(member, subst))) return true
                }
            }

            return false
        }

        if (Namespace.Values in ns) {
            if (processMembersWithDefaults({ it.functionList })) return true
            if (processMembersWithDefaults({ it.constantList })) return true
        }
        if (Namespace.Types in ns) {
            if (processMembersWithDefaults({ it.typeAliasList })) return true
        }
        return false
    }

    val (traits, inherent) = lookup.findImplsAndTraits(type).partition { (it.element as? RsImplItem)?.traitRef != null }
    if (inherent.any { processTraitOrImpl(it, true) }) return true
    if (traits.any { processTraitOrImpl(it, false) }) return true
    return false
}

private fun processItemOrEnumVariantDeclarations(scope: RsCompositeElement, ns: Set<Namespace>, processor: RsResolveProcessor, withPrivateImports: Boolean = false): Boolean {
    when (scope) {
        is RsEnumItem -> {
            if (processAll(scope.enumBody?.enumVariantList.orEmpty(), processor)) return true
        }
        is RsMod -> {
            if (processItemDeclarations(scope, ns, processor, withPrivateImports)) return true
        }
    }

    return false
}

private fun processItemDeclarations(scope: RsItemsOwner, ns: Set<Namespace>, originalProcessor: RsResolveProcessor, withPrivateImports: Boolean): Boolean {
    val (starImports, itemImports) = scope.useItemList
        .filter { it.isPublic || withPrivateImports }
        .partition { it.isStarImport }

    // Handle shadowing of `use::*`, but only if star imports are present
    val directlyDeclaredNames = mutableSetOf<String>()
    val processor = if (starImports.isEmpty()) {
        originalProcessor
    } else {
        { e: ScopeEntry ->
            directlyDeclaredNames += e.name
            originalProcessor(e)
        }
    }


    // Unit like structs are both types and values
    for (struct in scope.structItemList) {
        if (struct.namespaces.intersect(ns).isNotEmpty() && processor(struct)) {
            return true
        }
    }

    if (Namespace.Types in ns) {
        for (modDecl in scope.modDeclItemList) {
            val name = modDecl.name ?: continue
            val mod = modDecl.reference.resolve() ?: continue
            if (processor(name, mod)) return true
        }

        if (processAll(scope.enumItemList, processor)
            || processAll(scope.modItemList, processor)
            || processAll(scope.traitItemList, processor)
            || processAll(scope.typeAliasList, processor)) {
            return true
        }

        if (scope is RsFile && scope.isCrateRoot) {
            val pkg = scope.containingCargoPackage

            if (pkg != null) {
                val findStdMod = { name: String ->
                    val crate = pkg.findDependency(name)?.crateRoot
                    scope.project.getPsiFor(crate)?.rustMod
                }

                // Rust injects implicit `extern crate std` in every crate root module unless it is
                // a `#![no_std]` crate, in which case `extern crate core` is injected. However, if
                // there is a (unstable?) `#![no_core]` attribute, nothing is injected.
                //
                // https://doc.rust-lang.org/book/using-rust-without-the-standard-library.html
                // The stdlib lib itself is `#![no_std]`, and the core is `#![no_core]`
                when (scope.attributes) {
                    RsFile.Attributes.NONE -> {
                        if (processor.lazy("std") { findStdMod("std") }) {
                            return true
                        }
                    }
                    RsFile.Attributes.NO_STD -> {
                        if (processor.lazy("core") { findStdMod("core") }) {
                            return true
                        }
                    }
                    RsFile.Attributes.NO_CORE -> {
                    }
                }
            }
        }

    }

    if (Namespace.Values in ns) {
        if (processAll(scope.functionList, processor)
            || processAll(scope.constantList, processor)) {
            return true
        }
    }

    for (fmod in scope.foreignModItemList) {
        if (processAll(fmod.functionList, processor)) return true
        if (processAll(fmod.constantList, processor)) return true
    }

    for (crate in scope.externCrateItemList) {
        val name = crate.alias?.name ?: crate.name ?: continue
        val mod = crate.reference.resolve() ?: continue
        if (processor(name, mod)) return true
    }

    fun processMultiResolveWithNs(name: String, ref: RsReference, processor: RsResolveProcessor): Boolean {
        // XXX: use items can legitimately resolve in both namespaces.
        // Because we must be lazy, we don't know up front how many times we
        // need to call the `processor`, so we need to calculate this lazily
        // if the processor scrutinizes at least the first element.

        // XXX: there are two `cfg`ed `boxed` modules in liballoc, so
        // we apply "first in the namespace wins" heuristic.
        var variants: List<RsNamedElement> = emptyList()
        val visitedNamespaces = EnumSet.noneOf(Namespace::class.java)
        if (processor.lazy(name) {
            variants = ref.multiResolve()
                .filterIsInstance<RsNamedElement>()
                .filter { ns.intersect(it.namespaces).isNotEmpty() }
            val first = variants.firstOrNull()
            if (first != null) {
                visitedNamespaces.addAll(first.namespaces)
            }
            first
        }) {
            return true
        }
        // `variants` will be populated if processor looked at the corresponding element
        for (element in variants.drop(1)) {
            if (element.namespaces.all { it in visitedNamespaces }) continue
            visitedNamespaces.addAll(element.namespaces)
            if (processor(name, element)) return true
        }
        return false
    }

    for (use in itemImports) {
        val globList = use.useGlobList
        if (globList == null) {
            val path = use.path ?: continue
            val name = use.alias?.name ?: path.referenceName ?: continue
            if (processMultiResolveWithNs(name, path.reference, processor)) return true
        } else {
            for (glob in globList.useGlobList) {
                val name = glob.alias?.name
                    ?: (if (glob.isSelf) use.path?.referenceName else null)
                    ?: glob.referenceName
                    ?: continue
                if (processMultiResolveWithNs(name, glob.reference, processor)) return true
            }
        }
    }

    if (originalProcessor(ScopeEvent.STAR_IMPORTS)) {
        return false
    }
    for (use in starImports) {
        val basePath = use.path
        val mod = (if (basePath != null) basePath.reference.resolve() else use.crateRoot)
            ?: continue

        val found = processItemOrEnumVariantDeclarations(mod, ns,
            { it.name !in directlyDeclaredNames && originalProcessor(it) },
            withPrivateImports = basePath != null && isSuperChain(basePath)
        )
        if (found) return true
    }

    return false
}

private fun processLexicalDeclarations(scope: RsCompositeElement, cameFrom: PsiElement, ns: Set<Namespace>, processor: RsResolveProcessor): Boolean {
    check(cameFrom.context == scope)

    fun processPattern(pattern: RsPat, processor: RsResolveProcessor): Boolean {
        val boundNames = PsiTreeUtil.findChildrenOfType(pattern, RsPatBinding::class.java)
        return processAll(boundNames, processor)
    }

    fun processCondition(condition: RsCondition?, processor: RsResolveProcessor): Boolean {
        if (condition == null || condition == cameFrom) return false
        val pat = condition.pat
        if (pat != null && processPattern(pat, processor)) return true
        return false
    }

    when (scope) {
        is RsMod -> {
            if (processItemDeclarations(scope, ns, processor, withPrivateImports = true)) return true
        }

        is RsStructItem,
        is RsEnumItem,
        is RsTypeAlias -> {
            scope as RsGenericDeclaration
            if (processAll(scope.typeParameters, processor)) return true
        }

        is RsTraitOrImpl -> {
            if (processAll(scope.typeParameters, processor)) return true
            if (processor("Self", scope)) return true
        }

        is RsFunction -> {
            if (Namespace.Types in ns) {
                if (processAll(scope.typeParameters, processor)) return true
            }
            if (Namespace.Values in ns) {
                val selfParam = scope.selfParameter
                if (selfParam != null && processor("self", selfParam)) return true

                for (parameter in scope.valueParameters) {
                    val pat = parameter.pat ?: continue
                    if (processPattern(pat, processor)) return true
                }
            }
        }

        is RsBlock -> {
            // We want to filter out
            // all non strictly preceding let declarations.
            //
            // ```
            // let x = 92; // visible
            // let x = x;  // not visible
            //         ^ context.place
            // let x = 62; // not visible
            // ```
            val visited = mutableSetOf<String>()
            if (Namespace.Values in ns) {
                val shadowingProcessor = { e: ScopeEntry ->
                    (e.name !in visited) && run {
                        visited += e.name
                        processor(e)
                    }
                }

                for (stmt in scope.stmtList.asReversed()) {
                    val pat = (stmt as? RsLetDecl)?.pat ?: continue
                    if (PsiUtilCore.compareElementsByPosition(cameFrom, stmt) < 0) continue
                    if (stmt == cameFrom) continue
                    if (processPattern(pat, shadowingProcessor)) return true
                }
            }

            return processItemDeclarations(scope, ns, processor, withPrivateImports = true)
        }

        is RsForExpr -> {
            if (scope.expr == cameFrom) return false
            if (Namespace.Values !in ns) return false
            val pat = scope.pat
            if (pat != null && processPattern(pat, processor)) return true
        }

        is RsIfExpr -> {
            if (Namespace.Values !in ns) return false
            return processCondition(scope.condition, processor)
        }
        is RsWhileExpr -> {
            if (Namespace.Values !in ns) return false
            return processCondition(scope.condition, processor)
        }

        is RsLambdaExpr -> {
            if (Namespace.Values !in ns) return false
            for (parameter in scope.valueParameterList.valueParameterList) {
                val pat = parameter.pat
                if (pat != null && processPattern(pat, processor)) return true
            }
        }

        is RsMatchArm -> {
            // Rust allows to defined several patterns in the single match arm,
            // but they all must bind the same variables, hence we can inspect
            // only the first one.
            if (cameFrom in scope.patList) return false
            if (Namespace.Values !in ns) return false
            val pat = scope.patList.firstOrNull()
            if (pat != null && processPattern(pat, processor)) return true

        }
    }
    return false
}

private fun processNestedScopesUpwards(scopeStart: RsCompositeElement, processor: RsResolveProcessor, ns: Set<Namespace>): Boolean {
    val prevScope = mutableSetOf<String>()
    walkUp(scopeStart, { it is RsMod }) { cameFrom, scope ->
        val currScope = mutableListOf<String>()
        val shadowingProcessor = { e: ScopeEntry ->
            e.name !in prevScope && run {
                currScope += e.name
                processor(e)
            }
        }
        if (processLexicalDeclarations(scope, cameFrom, ns, shadowingProcessor)) return@walkUp true
        prevScope.addAll(currScope)
        false
    }

    val preludeFile = scopeStart.containingCargoPackage?.findDependency("std")?.crateRoot
        ?.findFileByRelativePath("../prelude/v1.rs")
    val prelude = scopeStart.project.getPsiFor(preludeFile)?.rustMod
    if (prelude != null && processItemDeclarations(prelude, ns, { v -> v.name !in prevScope && processor(v) }, false)) return true

    return false
}

// There's already similar functions in TreeUtils, should use it
private fun walkUp(
    start: PsiElement,
    stopAfter: (RsCompositeElement) -> Boolean,
    stopAtFileBoundary: Boolean = true,
    processor: (cameFrom: PsiElement, scope: RsCompositeElement) -> Boolean
): Boolean {
    var cameFrom = start
    var scope = start.context as RsCompositeElement?
    while (scope != null) {
        if (processor(cameFrom, scope)) return true
        if (stopAfter(scope)) break
        cameFrom = scope
        scope = scope.context as? RsCompositeElement
        if (!stopAtFileBoundary) {
            if (scope == null && cameFrom is RsFile) {
                scope = cameFrom.`super`
            }
        }
    }
    return false
}

private operator fun RsResolveProcessor.invoke(name: String, e: RsCompositeElement, subst: Substitution = emptySubstitution): Boolean {
    return this(SimpleScopeEntry(name, e, subst))
}

private fun RsResolveProcessor.lazy(name: String, e: () -> RsCompositeElement?): Boolean {
    return this(LazyScopeEntry(name, lazy(e)))
}

private operator fun RsResolveProcessor.invoke(e: RsNamedElement): Boolean {
    val name = e.name ?: return false
    return this(name, e)
}

private operator fun RsResolveProcessor.invoke(e: BoundElement<RsNamedElement>): Boolean {
    val name = e.element.name ?: return false
    return this(SimpleScopeEntry(name, e.element, e.subst))
}

private fun processAll(elements: Collection<RsNamedElement>, processor: RsResolveProcessor): Boolean {
    for (e in elements) {
        if (processor(e)) return true
    }
    return false
}

private fun processAllWithSubst(
    elements: Collection<RsNamedElement>,
    subst: Substitution,
    processor: RsResolveProcessor
): Boolean {
    for (e in elements) {
        if (processor(BoundElement(e, subst))) return true
    }
    return false
}

private fun processAllBound(elements: Collection<RsNamedElement>, subst: Substitution, processor: RsResolveProcessor): Boolean {
    for (e in elements) {
        if (processor(BoundElement(e, subst))) return true
    }
    return false
}

private data class SimpleScopeEntry(
    override val name: String,
    override val element: RsCompositeElement,
    override val subst: Substitution = emptySubstitution
) : ScopeEntry

private class LazyScopeEntry(
    override val name: String,
    thunk: Lazy<RsCompositeElement?>
) : ScopeEntry {
    override val element: RsCompositeElement? by thunk

    override fun toString(): String = "LazyScopeEntry($name, $element)"
}

private fun isSuperChain(path: RsPath): Boolean {
    val qual = path.path
    return (path.referenceName == "super" || path.referenceName == "self") && (qual == null || isSuperChain(qual))
}
