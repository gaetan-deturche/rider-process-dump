package dev.processdump

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.updateSettings.impl.PluginUpdateSourceService
import com.intellij.openapi.updateSettings.impl.UpdateSettings

/**
 * Rider 2026.3 updates a plugin only from its recorded source, and a disk install has none:
 * register this plugin's repository and, when unset, its "Updates from" source. Never overrides
 * a source the user picked or removes other repositories. Both APIs are `impl`, hence the guards.
 */
class UpdateSourceRegistration : AppLifecycleListener {
    override fun appStarted() {
        runCatching {
            val hosts = UpdateSettings.getInstance().storedPluginHosts
            if (UPDATE_REPOSITORY !in hosts) hosts.add(UPDATE_REPOSITORY)
        }.onFailure { LOG.warn("Could not register the update repository", it) }
        runCatching {
            if (PluginUpdateSourceService.isFunctionalitySupported()) {
                val sources = PluginUpdateSourceService.getInstance()
                val descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
                if (descriptor != null && sources.isMissingPluginUpdateSource(descriptor)) {
                    sources.setPluginUpdateSourceId(
                        descriptor.pluginId,
                        sources.createCustomRepositoryPluginUpdateSourceId(UPDATE_REPOSITORY),
                    )
                }
            }
        }.onFailure { LOG.warn("Could not set the plugin update source", it) }
    }

    private companion object {
        const val PLUGIN_ID = "dev.processdump"
        // Must equal the stored host string exactly (no trailing slash).
        const val UPDATE_REPOSITORY = "https://raw.githubusercontent.com/gaetan-deturche/rider-process-dump/main/updatePlugins.xml"
        val LOG = logger<UpdateSourceRegistration>()
    }
}
