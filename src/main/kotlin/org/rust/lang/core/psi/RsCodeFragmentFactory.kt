/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.lang.core.psi.ext.CARGO_WORKSPACE
import org.rust.lang.core.psi.ext.RsCompositeElement
import org.rust.lang.core.psi.ext.cargoWorkspace
import org.rust.lang.core.psi.ext.setContext

class RsCodeFragmentFactory(private val project: Project) {
    private val psiFactory = RsPsiFactory(project)

    fun createCrateRelativePath(pathText: String, target: CargoWorkspace.Target): RsPath? {
        check(pathText.startsWith("::"))
        val vFile = target.crateRoot ?: return null
        val crateRoot = PsiManager.getInstance(project).findFile(vFile) as? RsFile ?: return null
        return psiFactory.tryCreatePath(pathText)?.apply { setContext(crateRoot) }
    }

    fun createPath(path: String, context: RsCompositeElement): RsPath? =
        psiFactory.tryCreatePath(path)?.apply {
            setContext(context)
            containingFile?.putUserData(CARGO_WORKSPACE, context.cargoWorkspace)
        }
}
