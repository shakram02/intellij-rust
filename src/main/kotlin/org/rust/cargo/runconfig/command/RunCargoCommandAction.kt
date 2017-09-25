/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.command

import com.intellij.openapi.actionSystem.AnActionEvent
import org.rust.cargo.icons.CargoIcons
import org.rust.cargo.runconfig.ui.RunCargoCommandDialog

class RunCargoCommandAction : RunCargoCommandActionBase(CargoIcons.ICON) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ws = getAppropriateCargoProject(e)?.workspace
        val dialog = RunCargoCommandDialog(project, ws)
        if (!dialog.showAndGet()) return

        runCommand(project, dialog.getCargoCommandLine())
    }

}
