package eu.kanade.tachiyomi.extension.all.netraragi

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class NETraragiFactory : SourceFactory {
    override fun createSources(): List<Source> {
        val firstLrr = NETraragi("1")
        val lrrCount = firstLrr.preferences.getString(NETraragi.EXTRA_SOURCES_COUNT_KEY, NETraragi.EXTRA_SOURCES_COUNT_DEFAULT)!!.toInt()

        return buildList(lrrCount) {
            add(firstLrr)
            for (i in 2..lrrCount) {
                add(NETraragi("$i"))
            }
        }
    }
}
