package com.opussessionlogger;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Opus Session Logger",
	description = "Logs exact login/logout timestamps and per-skill XP snapshots for each play session to a local file",
	tags = {"session", "log", "xp", "tracker"}
)
public class OpusSessionLoggerPlugin extends Plugin
{
	private static final String EXPORT_DIR_NAME = "opus-session-logger";
	private static final String EXPORT_FILE_NAME = "sessions.jsonl";

	@Inject
	private Client client;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	// Rolling last-known skill state, replaced entry-by-entry on every
	// StatChanged. The client wipes skill data by the time LOGIN_SCREEN
	// fires, so the logout event reads from this map, not the live client.
	private final Map<String, Map<String, Integer>> lastKnownSkills = new LinkedHashMap<>();

	private boolean inSession;
	private boolean pendingLoginSnapshot;
	private String sessionUuid;
	private int sessionWorld = -1;
	private String playerName;

	@Override
	protected void startUp()
	{
		log.info("Opus Session Logger started");
	}

	@Override
	protected void shutDown()
	{
		// Best-effort close if the plugin is disabled mid-session.
		if (inSession)
		{
			writeEvent("logout", "plugin_shutdown");
			inSession = false;
			sessionUuid = null;
		}
		pendingLoginSnapshot = false;
		lastKnownSkills.clear();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				// Fires on every region load too — only the first one after a
				// real login opens a session. XP isn't populated yet at this
				// exact event, so the snapshot waits for the next game tick.
				if (!inSession)
				{
					pendingLoginSnapshot = true;
				}
				break;
			case LOGIN_SCREEN:
				// The only state that ends a session. HOPPING, CONNECTION_LOST
				// and LOADING deliberately never split one.
				if (inSession)
				{
					writeEvent("logout", "login_screen");
					inSession = false;
					sessionUuid = null;
				}
				pendingLoginSnapshot = false;
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!pendingLoginSnapshot)
		{
			return;
		}
		pendingLoginSnapshot = false;

		sessionUuid = UUID.randomUUID().toString();
		sessionWorld = client.getWorld();
		final Player local = client.getLocalPlayer();
		playerName = local != null ? local.getName() : null;

		lastKnownSkills.clear();
		for (Skill skill : Skill.values())
		{
			putSkill(skill.getName(), client.getSkillExperience(skill), client.getRealSkillLevel(skill));
		}

		inSession = true;
		writeEvent("login", null);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		putSkill(event.getSkill().getName(), event.getXp(), event.getLevel());
	}

	private void putSkill(String name, int xp, int level)
	{
		final Map<String, Integer> entry = new LinkedHashMap<>();
		entry.put("xp", xp);
		entry.put("level", level);
		lastKnownSkills.put(name, entry);
	}

	private void writeEvent(String type, String reason)
	{
		final Map<String, Object> event = new LinkedHashMap<>();
		event.put("event", type);
		if (reason != null)
		{
			event.put("reason", reason);
		}
		event.put("session_uuid", sessionUuid);
		event.put("ts_ms", Instant.now().toEpochMilli());
		event.put("player", playerName);
		event.put("world", sessionWorld);
		event.put("skills", lastKnownSkills);

		// Serialize on the client thread (cheap, and the skill map is only
		// touched from this thread); append on the executor — blocking disk
		// IO must stay off the client thread.
		final String line = gson.toJson(event);
		executor.submit(() -> appendLine(line));
	}

	private void appendLine(String line)
	{
		try
		{
			final Path dir = new File(RuneLite.RUNELITE_DIR, EXPORT_DIR_NAME).toPath();
			Files.createDirectories(dir);
			Files.write(
				dir.resolve(EXPORT_FILE_NAME),
				(line + "\n").getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND
			);
		}
		catch (Exception ex)
		{
			log.warn("Failed to write session event", ex);
		}
	}
}
