package com.sempermechanics.semper.data.policy

import java.util.Locale

/**
 * A feature the backend can switch off for every build at once
 * (`/v1/config` `killSwitches`), for an incident: a bad backup format, a
 * share path leaking something, a restore bug. Switched back on by the next
 * config that no longer names it. Nothing local is deleted or locked.
 */
enum class KillSwitch(val wire: String) {
    /** Cloud backup as a whole: its screens, uploads and restores. */
    BACKUP("backup"),

    /** Share and export to other apps. */
    SHARE("share"),

    /** Pulling backups down: restore, and Save to Files from the cloud. */
    RESTORE("restore"),

    /** Sending analyses up; queued ones wait, still marked as waiting. */
    UPLOAD("upload"),
    ;

    companion object {
        /**
         * The switches named in [raw]. Case and surrounding space do not
         * matter; a name this build does not know is ignored, so the backend
         * can add one for newer builds without older ones misreading it.
         */
        fun parse(raw: Collection<String>?): Set<KillSwitch> = raw.orEmpty()
            .mapNotNull { name -> entries.firstOrNull { it.wire == name.trim().lowercase(Locale.ROOT) } }
            .toSet()

        /** The wire names of [switches], for storage. */
        fun wireNames(switches: Set<KillSwitch>): Set<String> = switches.mapTo(HashSet()) { it.wire }
    }
}
