package com.chatwikilinks;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class ChatWikiLinksTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(ChatWikiLinksPlugin.class);
        RuneLite.main(args);
    }
}