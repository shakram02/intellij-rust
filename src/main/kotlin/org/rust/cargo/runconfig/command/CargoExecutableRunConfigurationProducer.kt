/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.command

import com.intellij.execution.Location
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.rust.cargo.CargoConstants
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.runconfig.mergeWithDefault
import org.rust.cargo.toolchain.CargoCommandLine
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.ext.cargoWorkspace
import org.rust.lang.core.psi.ext.parentOfType

class CargoExecutableRunConfigurationProducer : RunConfigurationProducer<CargoCommandConfiguration>(CargoCommandConfigurationType()) {

    override fun isConfigurationFromContext(
        configuration: CargoCommandConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val location = context.location ?: return false
        val target = findBinaryTarget(location) ?: return false

        return configuration.canBeFrom(target.cargoCommandLine)
    }

    override fun setupConfigurationFromContext(
        configuration: CargoCommandConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val location = context.location ?: return false
        val target = findBinaryTarget(location) ?: return false
        val fn = location.psiElement.parentOfType<RsFunction>()
        val source = if (fn != null && isMainFunction(fn)) fn else context.psiLocation?.containingFile
        sourceElement.set(source)

        configuration.name = target.configurationName
        val cmd = target.cargoCommandLine.mergeWithDefault(configuration)
        configuration.setFromCmd(cmd)
        return true
    }

    private class ExecutableTarget(
        target: CargoWorkspace.Target,
        kind: String
    ) {
        val configurationName: String = "Run ${target.name}"

        val cargoCommandLine = CargoCommandLine(
            CargoConstants.Commands.RUN,
            listOf("--$kind", target.name),
            workingDirectory = target.pkg.rootDirectory
        )
    }

    companion object {
        fun isMainFunction(fn: RsFunction): Boolean {
            val ws = fn.cargoWorkspace ?: return false
            return fn.name == "main" && findBinaryTarget(ws, fn.containingFile.virtualFile) != null
        }

        private fun findBinaryTarget(location: Location<*>): ExecutableTarget? {
            val file = location.virtualFile ?: return null
            val rsFile = PsiManager.getInstance(location.project).findFile(file) as? RsFile ?: return null
            val ws = rsFile.cargoWorkspace ?: return null
            return findBinaryTarget(ws, file)
        }

        private fun findBinaryTarget(ws: CargoWorkspace, file: VirtualFile): ExecutableTarget? {
            // TODO: specify workspace package here once
            // https://github.com/rust-lang/cargo/issues/3529
            // is fixed
            val target = ws.findTargetByCrateRoot(file) ?: return null
            return when {
                target.isBin -> ExecutableTarget(target, "bin")
                target.isExample -> ExecutableTarget(target, "example")
                else -> null
            }
        }
    }
}
