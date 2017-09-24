/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.command

import com.intellij.execution.Executor
import com.intellij.execution.ExternalizablePath
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.model.cargoProjects
import org.rust.cargo.project.settings.toolchain
import org.rust.cargo.runconfig.CargoRunState
import org.rust.cargo.runconfig.ui.CargoRunConfigurationEditorForm
import org.rust.cargo.toolchain.BacktraceMode
import org.rust.cargo.toolchain.CargoCommandLine
import org.rust.cargo.toolchain.RustChannel
import org.rust.cargo.toolchain.RustToolchain
import java.nio.file.Path
import java.nio.file.Paths

/**
 * This class describes a Run Configuration.
 * It is basically a bunch of values which are persisted to .xml files inside .idea,
 * or displayed in the GUI form. It has to be mutable to satisfy various IDE's APIs.
 */
class CargoCommandConfiguration(
    project: Project,
    name: String,
    factory: ConfigurationFactory
) : LocatableConfigurationBase(project, factory, name),
    RunConfigurationWithSuppressedDefaultDebugAction {

    var channel: RustChannel = RustChannel.DEFAULT
    var command: String = "run"
    var nocapture: Boolean = true
    var backtrace: BacktraceMode = BacktraceMode.SHORT
    var workingDirectory: Path? = null
    var env: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.writeEnum("channel", channel)
        element.writeString("command", command)
        element.writeBool("nocapture", nocapture)
        element.writeEnum("backtrace", backtrace)
        element.writePath("workingDirectory", workingDirectory)
        env.writeExternal(element)
    }

    /**
     * If you change serialization, make sure that the old variant is still
     * readable for several releases.
     */
    override fun readExternal(element: Element) {
        super.readExternal(element)
        val oldStyle = element.children.find { it.name == "parameters" }
        // BACKCOMPAT: can be removed after a couple of releases
        if (oldStyle != null) {
            data class SerializableCargoCommandLine(
                var command: String = "",
                var additionalArguments: List<String> = mutableListOf(),
                var backtraceMode: Int = BacktraceMode.DEFAULT.index,
                var channel: Int = RustChannel.DEFAULT.index,
                var environmentVariables: Map<String, String> = mutableMapOf(),
                var nocapture: Boolean = true
            )

            val cmd = SerializableCargoCommandLine()
            XmlSerializer.deserializeInto(cmd, oldStyle)
            channel = RustChannel.fromIndex(cmd.channel)
            command = ParametersListUtil.join(cmd.command, *cmd.additionalArguments.toTypedArray())
            nocapture = cmd.nocapture
            backtrace = BacktraceMode.fromIndex(cmd.backtraceMode)
            env = EnvironmentVariablesData.create(cmd.environmentVariables, true)
            return
        }

        element.readEnum<RustChannel>("channel")?.let { channel = it }
        element.readString("command")?.let { command = it }
        element.readBool("nocapture")?.let { nocapture = it }
        element.readEnum<BacktraceMode>("backtrace")?.let { backtrace = it }
        element.readPath("workingDirectory")?.let { workingDirectory = it }
        env = EnvironmentVariablesData.readExternal(element)
    }

    fun setFromCmd(cmd: CargoCommandLine) {
        channel = cmd.channel
        command = ParametersListUtil.join(cmd.command, *cmd.additionalArguments.toTypedArray())
        nocapture = cmd.nocapture
        backtrace = cmd.backtraceMode
        workingDirectory = cmd.workingDirectory
        env = cmd.environmentVariables
    }

    fun canBeFrom(cmd: CargoCommandLine): Boolean =
        command == ParametersListUtil.join(cmd.command, *cmd.additionalArguments.toTypedArray())

    @Throws(RuntimeConfigurationError::class)
    override fun checkConfiguration() {
        val config = clean()
        if (config is CleanConfiguration.Err) throw config.error
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        CargoRunConfigurationEditorForm(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? =
        clean().ok?.let { CargoRunState(environment, it) }

    sealed class CleanConfiguration {
        class Ok(
            val cmd: CargoCommandLine,
            val toolchain: RustToolchain,
            val cargoProject: CargoProject,
            val cargoProjectDirectory: VirtualFile
        ) : CleanConfiguration()

        class Err(val error: RuntimeConfigurationError) : CleanConfiguration()

        val ok: CleanConfiguration.Ok? get() = this as? Ok

        companion object {
            fun error(message: String) = Err(RuntimeConfigurationError(message))
        }
    }

    fun clean(): CleanConfiguration {
        val cmd = run {
            val args = ParametersListUtil.parse(command)
            if (args.isEmpty()) {
                return CleanConfiguration.error("No command specified")
            }
            CargoCommandLine(args.first(), args.drop(1), backtrace, channel, workingDirectory, env, nocapture)
        }

        val cargoProject = findCargoProject(project, command, workingDirectory)
            ?: return CleanConfiguration.error("Unable to determine Cargo project")

        val toolchain = project.toolchain
            ?: return CleanConfiguration.error("No Rust toolchain specified")

        if (!toolchain.looksLikeValidToolchain()) {
            return CleanConfiguration.error("Invalid toolchain: ${toolchain.presentableLocation}")
        }


        if (!toolchain.isRustupAvailable && channel != RustChannel.DEFAULT) {
            return CleanConfiguration.error("Channel '$channel' is set explicitly with no rustup available")
        }

        return CleanConfiguration.Ok(
            cmd,
            toolchain,
            cargoProject,
            cargoProject.rootDir
                ?: return CleanConfiguration.error("Cargo project does not exist")
        )
    }

    companion object {
        fun findCargoProject(project: Project, cmd: String, workingDirectory: Path?): CargoProject? {
            val cargoProjects = project.cargoProjects
            cargoProjects.allProjects.singleOrNull()?.let { return it }

            val manifestPath = run {
                val args = ParametersListUtil.parse(cmd)
                val idx = args.indexOf("--manifest-path")
                if (idx == -1) return@run null
                args.getOrNull(idx + 1)?.let { Paths.get(it) }
            }

            for (dir in listOfNotNull(manifestPath?.parent, workingDirectory)) {
                LocalFileSystem.getInstance().findFileByIoFile(dir.toFile())
                    ?.let { cargoProjects.findProjectForFile(it) }
                    ?.let { return it }
            }
            return null
        }
    }
}


private fun Element.writeString(name: String, value: String) {
    val opt = org.jdom.Element("option")
    opt.setAttribute("name", name)
    opt.setAttribute("value", value)
    addContent(opt)
}

private fun Element.readString(name: String): String? =
    children
        .find { it.name == "option" && it.getAttributeValue("name") == name }
        ?.getAttributeValue("value")


private fun Element.writeBool(name: String, value: Boolean) {
    writeString(name, value.toString())
}

private fun Element.readBool(name: String) = readString(name)?.toBoolean()

private fun <E : Enum<*>> Element.writeEnum(name: String, value: E) {
    writeString(name, value.name)
}

private inline fun <reified E : Enum<E>> Element.readEnum(name: String): E? {
    val variantName = readString(name) ?: return null
    return try {
        java.lang.Enum.valueOf(E::class.java, variantName)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun Element.writePath(name: String, value: Path?) {
    if (value != null) {
        val s = ExternalizablePath.urlValue(value.toString())
        writeString(name, s)
    }
}

private fun Element.readPath(name: String): Path? {
    return readString(name)?.let { Paths.get(ExternalizablePath.localPathValue(it)) }
}
