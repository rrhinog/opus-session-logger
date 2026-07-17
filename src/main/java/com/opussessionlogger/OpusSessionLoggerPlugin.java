package com.opussessionlogger;

import com.google.gson.Gson;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.StructComposition;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
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

	// Game ticks between checkpoint events (~0.6s per tick → ~5 minutes).
	// A checkpoint bounds what a hard crash can lose: a reader can close an
	// unpaired session at its last checkpoint instead of its login snapshot.
	private static final int CHECKPOINT_TICKS = 500;

	// Achievement diary completion varbits (gameval VarbitID). RAW values are
	// exported deliberately and decoded downstream, so a semantics surprise
	// (Karamja's legacy ATJUN_* trio predates the 0/1 pattern) is fixed by the
	// reader, not by a plugin rebuild.
	private static final Map<String, Map<String, Integer>> DIARY_VARBITS = new LinkedHashMap<>();

	static
	{
		DIARY_VARBITS.put("Ardougne", tiers(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE));
		DIARY_VARBITS.put("Desert", tiers(VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE));
		DIARY_VARBITS.put("Falador", tiers(VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE));
		DIARY_VARBITS.put("Fremennik", tiers(VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE));
		DIARY_VARBITS.put("Kandarin", tiers(VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE));
		DIARY_VARBITS.put("Karamja", tiers(VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE, VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE));
		DIARY_VARBITS.put("Kourend & Kebos", tiers(VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE));
		DIARY_VARBITS.put("Lumbridge & Draynor", tiers(VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE));
		DIARY_VARBITS.put("Morytania", tiers(VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE));
		DIARY_VARBITS.put("Varrock", tiers(VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE));
		DIARY_VARBITS.put("Western Provinces", tiers(VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE));
		DIARY_VARBITS.put("Wilderness", tiers(VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE));
	}

	private static Map<String, Integer> tiers(int easy, int medium, int hard, int elite)
	{
		final Map<String, Integer> m = new LinkedHashMap<>();
		m.put("Easy", easy);
		m.put("Medium", medium);
		m.put("Hard", hard);
		m.put("Elite", elite);
		return m;
	}

	// Combat Achievements: tier enums -> task structs (the ca-export mechanism).
	// Struct param 1308 = task name, 1306 = task id; completion is bit (id % 32)
	// of varp CA_TASK_COMPLETED_{id / 32}.
	private static final Map<Integer, String> CA_TIER_ENUMS = new LinkedHashMap<>();

	static
	{
		CA_TIER_ENUMS.put(3981, "Easy");
		CA_TIER_ENUMS.put(3982, "Medium");
		CA_TIER_ENUMS.put(3983, "Hard");
		CA_TIER_ENUMS.put(3984, "Elite");
		CA_TIER_ENUMS.put(3985, "Master");
		CA_TIER_ENUMS.put(3986, "Grandmaster");
	}

	private static final int CA_STRUCT_NAME_PARAM = 1308;
	private static final int CA_STRUCT_ID_PARAM = 1306;

	private static final int[] CA_VARPS = new int[]{
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1,
		VarPlayerID.CA_TASK_COMPLETED_2, VarPlayerID.CA_TASK_COMPLETED_3,
		VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7,
		VarPlayerID.CA_TASK_COMPLETED_8, VarPlayerID.CA_TASK_COMPLETED_9,
		VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13,
		VarPlayerID.CA_TASK_COMPLETED_14, VarPlayerID.CA_TASK_COMPLETED_15,
		VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19,
	};

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
	private int ticksSinceCheckpoint;

	@Override
	protected void startUp()
	{
		log.info("Opus Session Logger started");
	}

	@Override
	protected void shutDown()
	{
		// Close-out on plugin disable or client shutdown. Written SYNCHRONOUSLY:
		// an executor-queued write loses the race against JVM exit when the
		// client window is closed from in-game (verified 2026-07-15).
		if (inSession)
		{
			writeEventSync("logout", "plugin_shutdown");
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
		if (pendingLoginSnapshot)
		{
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
			ticksSinceCheckpoint = 0;
			writeEvent("login", null);
			// Completionist state (quests/diaries/CAs) reads varps and cache
			// enums, which are loaded by now — but wiped at the login screen,
			// so login-tick (not logout) is the reliable capture point.
			writeCompletionistEvent();
			return;
		}

		// Periodic checkpoint while in-session — bounds crash data loss to
		// ~5 minutes. The last checkpoint is the honest end-bound for a
		// session whose logout event never arrived.
		if (inSession && ++ticksSinceCheckpoint >= CHECKPOINT_TICKS)
		{
			ticksSinceCheckpoint = 0;
			writeEvent("checkpoint", null);
		}
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		// The client-close path. RuneLite does NOT call plugin shutDown() on
		// exit (verified 2026-07-15: window closed from in-game, no lifecycle
		// call, logout line lost) — it fires this event instead. waitFor()
		// makes the client block on our final write before the JVM exits.
		if (inSession)
		{
			final String line = buildEventLine("logout", "client_shutdown");
			inSession = false;
			sessionUuid = null;
			event.waitFor(executor.submit(() -> appendLine(line)));
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		// A death is irreversible, so it is worth a full skill snapshot at the
		// moment it happens. Other actors' deaths (NPCs, other players) are ignored.
		if (inSession && event.getActor() == client.getLocalPlayer())
		{
			writeEvent("death", null);
		}
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

	private String buildEventLine(String type, String reason)
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
		return gson.toJson(event);
	}

	private void writeEvent(String type, String reason)
	{
		// Serialize on the client thread (cheap, and the skill map is only
		// touched from this thread); append on the executor — blocking disk
		// IO must stay off the client thread.
		final String line = buildEventLine(type, reason);
		executor.submit(() -> appendLine(line));
	}

	private void writeEventSync(String type, String reason)
	{
		// Shutdown-only path: a queued write would race JVM exit and lose.
		appendLine(buildEventLine(type, reason));
	}

	private void writeCompletionistEvent()
	{
		final Map<String, Object> event = new LinkedHashMap<>();
		event.put("event", "completionist");
		event.put("session_uuid", sessionUuid);
		event.put("ts_ms", Instant.now().toEpochMilli());
		event.put("player", playerName);

		final Map<String, String> quests = new LinkedHashMap<>();
		for (Quest quest : Quest.values())
		{
			quests.put(quest.getName(), quest.getState(client).name());
		}
		event.put("quests", quests);

		final Map<String, Map<String, Integer>> diaries = new LinkedHashMap<>();
		DIARY_VARBITS.forEach((region, regionTiers) ->
		{
			final Map<String, Integer> values = new LinkedHashMap<>();
			regionTiers.forEach((tier, varbit) -> values.put(tier, client.getVarbitValue(varbit)));
			diaries.put(region, values);
		});
		event.put("diaries", diaries);

		final List<Map<String, Object>> combatAchievements = new ArrayList<>();
		for (Map.Entry<Integer, String> tierEntry : CA_TIER_ENUMS.entrySet())
		{
			final EnumComposition tierEnum = client.getEnum(tierEntry.getKey());
			for (int structId : tierEnum.getIntVals())
			{
				final StructComposition struct = client.getStructComposition(structId);
				final int id = struct.getIntValue(CA_STRUCT_ID_PARAM);
				final boolean completed =
					(client.getVarpValue(CA_VARPS[id / 32]) & (1 << (id % 32))) != 0;
				final Map<String, Object> task = new LinkedHashMap<>();
				task.put("id", id);
				task.put("name", struct.getStringValue(CA_STRUCT_NAME_PARAM));
				task.put("tier", tierEntry.getValue());
				task.put("completed", completed);
				combatAchievements.add(task);
			}
		}
		event.put("combat_achievements", combatAchievements);

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
