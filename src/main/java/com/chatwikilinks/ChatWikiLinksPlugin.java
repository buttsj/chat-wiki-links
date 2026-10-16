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

import com.google.inject.Provides;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;
import net.runelite.client.game.ItemManager;
import okhttp3.HttpUrl;

@Slf4j
@PluginDescriptor(
        name = "Chat Wiki Links",
        description = "Highlights keywords in game chat and adds a context menu option to open the OSRS Wiki",
        tags = {"chat", "wiki", "lookup"}
)
public class ChatWikiLinksPlugin extends Plugin {

    // These variables are to help with the OSRS Wiki
    static final HttpUrl WIKI_BASE = HttpUrl.get("https://oldschool.runescape.wiki");
    static final String UTM_SOURCE_KEY = "utm_source";
    static final String UTM_SOURCE_VALUE = "runelite";
    private static final String MENUOP_WIKI = "Wiki";

    // These variables are to help with identifying when a user is hovering over hyperlink text
    private static final int DEFAULT_CHAR_WIDTH = 6;
    private static final int HOVER_PADDING_PIXELS = 2;
    private static final int CHAT_LINE_HEIGHT = 15;

    @Inject
    private ChatWikiLinksConfig config;

    @Inject
    private ClientThread clientThread;

    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private KeyManager keyManager;

    @Inject
    private ChatWikiLinksHotkeyListener hotkeyListener;

    private boolean isKeybindPressed = false;

    public void setIsKeyBindPressed(boolean isKeybindPressed) {
        this.isKeybindPressed = isKeybindPressed;
    }

    @Provides
    ChatWikiLinksConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ChatWikiLinksConfig.class);
    }

    // Store the game items which should be linkable
    private final Map<String, String> baseItemCache = new HashMap<>();
    // Additional list of words that the user specified to hyperlink
    private final Map<String, String> allowlistItems = new HashMap<>();
    // Additional list of words that the user blocked from hyperlinks
    private final Set<String> blocklistKeywords = new HashSet<>();

    private final Set<String> sortedActiveKeywords = new TreeSet<>((a, b) -> {
        int aWords = a.split(" ").length;
        int bWords = b.split(" ").length;
        if (aWords != bWords)
        {
            return Integer.compare(bWords, aWords); // More words first
        }
        int lengthCompare = Integer.compare(b.length(), a.length());
        if (lengthCompare != 0)
        {
            return lengthCompare; // Longer length first
        }
        return a.compareTo(b); // Unique fallback to prevent dropping elements
    });

    public void setToggleStateChatLinks(boolean state)
    {
        clientThread.invoke(() -> {
            toggleChatLinks(state);
            client.refreshChat();
        });

    }

    @Override
    protected void startUp() throws Exception
    {
        keyManager.registerKeyListener(hotkeyListener);
        clientThread.invoke(() ->
        {
            if (client.getGameState() != null && client.getGameState().getState() >= GameState.LOGGED_IN.getState())
            {
                if (baseItemCache.isEmpty())
                {
                    buildBaseItemCache();
                    updateActiveKeywords();
                }
            }
        });
    }

    @Override
    protected void shutDown() throws Exception
    {
        keyManager.unregisterKeyListener(hotkeyListener);
        baseItemCache.clear();
        allowlistItems.clear();
        blocklistKeywords.clear();
        sortedActiveKeywords.clear();
        isKeybindPressed = false;
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged)
    {
        if (gameStateChanged.getGameState() == GameState.LOGGED_IN && baseItemCache.isEmpty())
        {
            buildBaseItemCache();
            updateActiveKeywords();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (event.getGroup().equals(ChatWikiLinksConfig.GROUP))
        {
            clientThread.invoke(() -> {
                updateActiveKeywords();
                client.refreshChat();
            });
        }
    }

    private void buildBaseItemCache()
    {
        baseItemCache.clear();

        for (int itemId = 0; itemId < client.getItemCount(); itemId++)
        {
            var composition = itemManager.getItemComposition(itemId);

            // Ensure this item composition is not null
            String name = composition.getName();
            if (name == null || name.isEmpty() || name.equalsIgnoreCase("null"))
            {
                continue;
            }

            // To ignore random non-wiki things, we only care about OSRS items with actions or a positive price value
            String[] actions = composition.getInventoryActions();
            boolean hasActions = false;
            if (actions != null)
            {
                for (String action : actions)
                {
                    if (action != null && !action.isEmpty())
                    {
                        hasActions = true;
                        break;
                    }
                }
            }

            if (!hasActions && composition.getPrice() <= 0)
            {
                continue;
            }

            // Now we prep this item name to easily scan for it with in-game chat messages
            String cleanName = name.replace('\u00A0', ' ').trim();
            String baseName = cleanName.replaceAll("\\s*\\(\\d+\\)", "").trim();

            for (String n : new String[]{cleanName, baseName})
            {
                if (n == null || n.isEmpty()) continue;
                String lowerName = n.toLowerCase();

                // Cache this item if it isn't already
                if (!baseItemCache.containsKey(lowerName))
                {
                    baseItemCache.put(lowerName, n);
                }

                // Because sometimes the game makes items plural, include those (e.g., Sapphire -> Sapphires)
                // The wiki is smart enough to redirect these words
                String pluralName = getPluralForm(lowerName);
                if (!baseItemCache.containsKey(pluralName))
                {
                    baseItemCache.put(pluralName, n);
                }
            }
        }
    }

    private String getPluralForm(String singular)
    {
        // The below code just deals with words and how to make them plural
        if (singular == null || singular.isEmpty())
        {
            return "";
        }

        if (singular.endsWith("y") && singular.length() > 1)
        {
            char secondLast = singular.charAt(singular.length() - 2);
            if (secondLast != 'a' && secondLast != 'e' && secondLast != 'i' && secondLast != 'o' && secondLast != 'u')
            {
                return singular.substring(0, singular.length() - 1) + "ies";
            }
        }

        if (singular.endsWith("s") || singular.endsWith("x") || singular.endsWith("z") || singular.endsWith("ch") || singular.endsWith("sh"))
        {
            return singular + "es";
        }

        return singular + "s";
    }

    private void updateActiveKeywords()
    {
        sortedActiveKeywords.clear();
        blocklistKeywords.clear();
        allowlistItems.clear();

        // Block these words
        String rawBlocklist = config.blocklist();
        if (rawBlocklist != null && !rawBlocklist.trim().isEmpty())
        {
            for (String item : rawBlocklist.split(","))
            {
                String clean = item.trim().toLowerCase();
                if (!clean.isEmpty())
                {
                    blocklistKeywords.add(clean);
                }
            }
        }

        // Default words to hyperlink, but skip if they are an above blocked word
        for (Map.Entry<String, String> entry : baseItemCache.entrySet())
        {
            String lowerName = entry.getKey();
            if (!blocklistKeywords.contains(lowerName))
            {
                sortedActiveKeywords.add(lowerName);

                // Include the plural version of the words because they count
                String pluralWord = getPluralForm(lowerName);
                allowlistItems.put(pluralWord, lowerName);
                sortedActiveKeywords.add(pluralWord);
            }
        }

        // Additional words the user specified to hyperlink
        String rawAllowlist = config.allowlist();
        if (rawAllowlist != null && !rawAllowlist.trim().isEmpty())
        {
            for (String item : rawAllowlist.split(","))
            {
                String rawWord = item.trim();
                String lowerWord = rawWord.toLowerCase();
                if (!lowerWord.isEmpty() && !blocklistKeywords.contains(lowerWord))
                {
                    allowlistItems.put(lowerWord, rawWord);
                    sortedActiveKeywords.add(lowerWord);
                }
            }
        }
    }

    private String getCanonicalName(String lowerKeyword)
    {
        // helper method to pull words if they exist in our caches (base items and allowed items)
        if (allowlistItems.containsKey(lowerKeyword))
        {
            return allowlistItems.get(lowerKeyword);
        }
        return baseItemCache.get(lowerKeyword);
    }

    private boolean isKeybindConfigured()
    {
        Keybind keybind = config.hotkey();
        return keybind != null && !Keybind.NOT_SET.equals(keybind);
    }

    private void toggleChatLinks(boolean enable)
    {
        // Loop over all messages in the game chat and process them with hyperlinks or undo the hyperlinks
        for (net.runelite.api.MessageNode messageNode : client.getMessages())
        {
            if (messageNode == null)
            {
                continue;
            }

            String currentText = messageNode.getValue();
            if (currentText == null)
            {
                continue;
            }

            if (enable)
            {
                String processed = processMessageText(currentText);
                if (!processed.equals(currentText))
                {
                    messageNode.setValue(processed);
                }
            }
            else
            {
                // Only set the value if the text actually contains the tags you added
                if (currentText.contains("<col=0000ff><u>"))
                {
                    String reverted = currentText.replace("<col=0000ff><u>", "").replace("</u></col>", "");
                    messageNode.setValue(reverted);
                }
            }
        }

        client.refreshChat();
    }

    private String processMessageText(String inputMessage)
    {
        if (inputMessage == null || inputMessage.isEmpty())
        {
            return inputMessage;
        }

        // Preserve original breaks and formatting
        String workingMessage = inputMessage.replace('\u00A0', ' ');

        // Use clean text ONLY to locate word match positions/boundaries
        String cleanText = Text.removeTags(workingMessage.replace("<br>", "\n").replace("<br/>", "\n"));
        if (cleanText.isEmpty())
        {
            return inputMessage;
        }

        String lowerClean = cleanText.toLowerCase();
        List<String> matchedKeywords = new ArrayList<>();

        // Identify which active keywords are present in this message
        for (String lowerItem : sortedActiveKeywords)
        {
            int idx = lowerClean.indexOf(lowerItem);
            if (idx != -1)
            {
                // Verify word boundaries
                int endIdx = idx + lowerItem.length();
                boolean validStart = (idx == 0 || !Character.isLetterOrDigit(cleanText.charAt(idx - 1)));
                boolean validEnd = (endIdx == cleanText.length() || !Character.isLetterOrDigit(cleanText.charAt(endIdx)));

                if (validStart && validEnd)
                {
                    matchedKeywords.add(lowerItem);
                }
            }
        }

        if (matchedKeywords.isEmpty())
        {
            return inputMessage;
        }

        // Perform targeted replacements directly on workingMessage so inline tags (like <col=ff0000>313</col>) remain intact!
        String result = workingMessage;
        for (String kw : matchedKeywords)
        {
            // Case-insensitive replacement that preserves original keyword casing and all surrounding tags
            String regex = "(?i)\\b(" + java.util.regex.Pattern.quote(kw) + ")\\b";
            result = result.replaceAll(regex, "<col=0000ff><u>$1</u></col>");
        }

        return result;
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage)
    {
        if (chatMessage == null || chatMessage.getMessage() == null)
        {
            return;
        }

        ChatMessageType chatMessageType = chatMessage.getType();
        if (chatMessageType != ChatMessageType.GAMEMESSAGE && chatMessageType != ChatMessageType.SPAM)
        {
            return;
        }

        if (isKeybindConfigured() && !isKeybindPressed)
        {
            return;
        }

        String rawMessage = chatMessage.getMessage().replace('\u00A0', ' ');
        String processed = processMessageText(rawMessage);

        if (!processed.equals(rawMessage) && chatMessage.getMessageNode() != null)
        {
            chatMessage.getMessageNode().setValue(processed);
            client.refreshChat();
        }
    }

    private static class MatchRange
    {
        int start;
        int end;

        MatchRange(int start, int end)
        {
            this.start = start;
            this.end = end;
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        Widget chatboxMessage = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
        
        if (chatboxMessage == null || chatboxMessage.isHidden())
        {
            return;
        }

        // Ignore this if the keybind is set but users haven't pressed the key
        if (isKeybindConfigured() && !isKeybindPressed)
        {
            return;
        }

        Widget[] dynamicChildren = chatboxMessage.getDynamicChildren();
        if (dynamicChildren == null)
        {
            return;
        }

        // Because some game text is formatted in the chat differently (see: Herb sack vs Gem sack) we need to adjust our menu entry to account for line breaks.
        // We also use some static sizes for line height, character width and padding.
        int mouseX = client.getMouseCanvasPosition().getX();
        int mouseY = client.getMouseCanvasPosition().getY();


        for (Widget child : dynamicChildren)
        {
            if (child != null)
            {
                String rawText = child.getText();
                if (rawText == null || rawText.isEmpty())
                {
                    continue;
                }

                int startX = child.getCanvasLocation().getX();
                int startY = child.getCanvasLocation().getY();

                // Split the widget text by <br> or <br/> to handle multi-line messages correctly
                String[] lines = rawText.split("<br\\/?>");

                for (int lineIndex = 0; lineIndex < lines.length; lineIndex++)
                {
                    int lineStartY = startY + (lineIndex * CHAT_LINE_HEIGHT);
                    int lineEndY = lineStartY + CHAT_LINE_HEIGHT;

                    if (mouseY >= lineStartY && mouseY <= lineEndY)
                    {
                        String lineRaw = lines[lineIndex];
                        String cleanLine = Text.removeTags(lineRaw).replace('\u00A0', ' ');
                        if (cleanLine.isEmpty())
                        {
                            continue;
                        }

                        String lowerLine = cleanLine.toLowerCase();

                        for (String lowerItem : sortedActiveKeywords)
                        {
                            int idx = 0;
                            while ((idx = lowerLine.indexOf(lowerItem, idx)) != -1)
                            {
                                int endIdx = idx + lowerItem.length();

                                boolean validStart = (idx == 0 || !Character.isLetterOrDigit(cleanLine.charAt(idx - 1)));
                                boolean validEnd = (endIdx == cleanLine.length() || !Character.isLetterOrDigit(cleanLine.charAt(endIdx)));

                                if (validStart && validEnd)
                                {
                                    int itemStartX = startX + (idx * DEFAULT_CHAR_WIDTH);
                                    int itemEndX = startX + (endIdx * DEFAULT_CHAR_WIDTH);

                                    if (mouseX >= itemStartX - HOVER_PADDING_PIXELS && mouseX <= itemEndX + HOVER_PADDING_PIXELS)
                                    {
                                        String canonicalName = getCanonicalName(lowerItem);
                                        if (canonicalName != null && !canonicalName.isEmpty())
                                        {
                                            String capitalizedName = canonicalName.substring(0, 1).toUpperCase() + canonicalName.substring(1);
                                            client.createMenuEntry(-1)
                                                    .setTarget("<col=ff9040>" + capitalizedName + "</col>")
                                                    .setOption(MENUOP_WIKI)
                                                    .setType(MenuAction.RUNELITE)
                                                    .onClick(e -> openWiki(canonicalName));
                                            return;
                                        }
                                    }
                                }
                                idx++;
                            }
                        }
                    }
                }
            }
        }
    }

    private void openWiki(String keyword)
    {
        if (keyword == null || keyword.isEmpty())
        {
            return;
        }

        HttpUrl.Builder urlBuilder = WIKI_BASE.newBuilder();
        urlBuilder.addPathSegments("w/Special:Lookup")
                .addQueryParameter("name", keyword)
                .addQueryParameter(UTM_SOURCE_KEY, UTM_SOURCE_VALUE);

        HttpUrl url = urlBuilder.build();
        LinkBrowser.browse(url.toString());
    }
}