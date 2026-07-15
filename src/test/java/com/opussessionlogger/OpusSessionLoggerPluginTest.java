package com.opussessionlogger;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class OpusSessionLoggerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OpusSessionLoggerPlugin.class);
		RuneLite.main(args);
	}
}
