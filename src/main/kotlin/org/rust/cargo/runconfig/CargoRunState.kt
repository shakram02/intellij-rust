/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.vfs.VirtualFile
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import org.rust.cargo.runconfig.filters.RsBacktraceFilter
import org.rust.cargo.runconfig.filters.RsConsoleFilter
import org.rust.cargo.runconfig.filters.RsExplainFilter
import org.rust.cargo.runconfig.filters.RsPanicFilter
import org.rust.cargo.toolchain.CargoCommandLine
import org.rust.cargo.toolchain.RustToolchain
import org.rust.utils.pathAsPath

class CargoRunState(
    environment: ExecutionEnvironment,
    config: CargoCommandConfiguration.CleanConfiguration.Ok
) : CommandLineState(environment) {
    private val toolchain: RustToolchain = config.toolchain
    private val cargoProjectDirectory: VirtualFile = config.cargoProjectDirectory
    private val commandLine: CargoCommandLine = config.cmd

    init {
        consoleBuilder.addFilter(RsConsoleFilter(environment.project, cargoProjectDirectory))
        consoleBuilder.addFilter(RsExplainFilter())
        consoleBuilder.addFilter(RsPanicFilter(environment.project, cargoProjectDirectory))
        consoleBuilder.addFilter(RsBacktraceFilter(cargoProjectDirectory, environment.project, config.cargoProject.workspace))
    }

    override fun startProcess(): ProcessHandler {
        val cmd = toolchain.cargo(cargoProjectDirectory.pathAsPath)
            .toColoredCommandLine(commandLine)
            // Explicitly use UTF-8.
            // Even though default system encoding is usually not UTF-8 on windows,
            // most Rust programs are UTF-8 only.
            .withCharset(Charsets.UTF_8)

        val handler = KillableColoredProcessHandler(cmd)
        ProcessTerminatedListener.attach(handler) // shows exit code upon termination
        return handler
    }
}
