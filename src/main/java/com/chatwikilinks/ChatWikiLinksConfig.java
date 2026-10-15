/*
 * Copyright (c) 2026, Jack <https://github.com/buttsj>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.chatwikilinks;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

@ConfigGroup(ChatWikiLinksConfig.GROUP)
public interface ChatWikiLinksConfig extends Config
{
    String GROUP = "chatwikilinks";

    @ConfigSection(
            name = "Filters",
            description = "Custom word filters (allow/block).",
            position = 0,
            closedByDefault = true
    )
    String filterLists = "filterLists";

    @ConfigItem(
            keyName = "allowlist",
            name = "Custom allowed list",
            description = "List of allowed words or phrases to include for Wiki linking, separated by commas.",
            position = 1,
            section = filterLists
    )
    default String allowlist() { return ""; }

    @ConfigItem(
            keyName = "blocklist",
            name = "Custom block list",
            description = "List of blocked words or phrases to exclude from Wiki linking, separated by commas.",
            position = 2,
            section = filterLists
    )
    default String blocklist() { return ""; }

    @ConfigItem(
            keyName = "hotkey",
            name = "Hotkey display Wiki links",
            description = "Enable a hotkey to display Wiki hyperlinks in chat.",
            position = 3
    )
    default Keybind hotkey() { return Keybind.NOT_SET; }
}