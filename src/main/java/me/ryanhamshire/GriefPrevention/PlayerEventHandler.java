/*
    GriefPrevention Server Plugin for Minecraft
    Copyright (C) 2011 Ryan Hamshire

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ryanhamshire.GriefPrevention;

import me.ryanhamshire.GriefPrevention.events.VisualizationEvent;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BlockIterator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

class PlayerEventHandler implements Listener 
{
	private DataStore dataStore;
	private GriefPrevention instance;

	//timestamps of login and logout notifications in the last minute
	private ArrayList<Long> recentLoginLogoutNotifications = new ArrayList<Long>();
	
	//regex pattern for the "how do i claim land?" scanner
	private Pattern howToClaimPattern = null;
	
	//typical constructor, yawn
	PlayerEventHandler(DataStore dataStore, GriefPrevention plugin)
	{
		this.dataStore = dataStore;
		this.instance = plugin;
	}
	
	//when a player chats, monitor for spam
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	synchronized void onPlayerChat (AsyncPlayerChatEvent event)
	{		
		Player player = event.getPlayer();
		if(!player.isOnline())
		{
			event.setCancelled(true);
			return;
		}
		
		String message = event.getMessage();
		
		boolean muted = this.handlePlayerChat(player, message, event);
		Set<Player> recipients = event.getRecipients();
		
		//muted messages go out to only the sender
		if(muted)
		{
		    recipients.clear();
		    recipients.add(player);
		}

		//remaining messages
		else
		{
		    //enter in abridged chat logs
		    makeSocialLogEntry(player.getName(), message);
		}
	}
	
	//returns true if the message should be muted, true if it should be sent 
	private boolean handlePlayerChat(Player player, String message, PlayerEvent event)
	{
		//FEATURE: automatically educate players about claiming land
		//watching for message format how*claim*, and will send a link to the basics video
		if(this.howToClaimPattern == null)
		{
			this.howToClaimPattern = Pattern.compile(this.dataStore.getMessage(Messages.HowToClaimRegex), Pattern.CASE_INSENSITIVE);
		}

		if(this.howToClaimPattern.matcher(message).matches())
		{
			instance.sendMessage(player, TextMode.Info, Messages.SurvivalBasicsVideo2, 10L, DataStore.SURVIVAL_VIDEO_URL);
		}
		
		//FEATURE: automatically educate players about the /trapped command
		//check for "trapped" or "stuck" to educate players about the /trapped command
		String trappedwords = this.dataStore.getMessage(
		    Messages.TrappedChatKeyword
		);
		if (!trappedwords.isEmpty()) {
		    String[] checkWords = trappedwords.split(";");

		    for (String checkWord : checkWords) {
			if (!message.contains("/trapped")
			    && message.contains(checkWord))
			{
			    instance.sendMessage(
				    player,
				    TextMode.Info, 
				    Messages.TrappedInstructions,
				    10L
			    );
			    break;
			}
		    }
		}

        return false;
	}
	
    static int longestNameLength = 10;
	static void makeSocialLogEntry(String name, String message)
	{
        StringBuilder entryBuilder = new StringBuilder(name);
        for(int i = name.length(); i < longestNameLength; i++)
        {
            entryBuilder.append(' ');
        }
        entryBuilder.append(": " + message);
        
        longestNameLength = Math.max(longestNameLength, name.length());
        //TODO: cleanup static
        GriefPrevention.AddLogEntry(entryBuilder.toString(), CustomLogEntryTypes.SocialActivity, true);
    }

	//when a player successfully joins the server...
	@SuppressWarnings("deprecation")
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
	void onPlayerJoin(PlayerJoinEvent event)
	{
		Player player = event.getPlayer();
		UUID playerID = player.getUniqueId();
		
		//note login time
		Date nowDate = new Date();
        long now = nowDate.getTime();

		//if player has never played on the server before...
		if(!player.hasPlayedBefore())
		{
		    //if in survival claims mode, send a message about the claim basics video (except for admins - assumed experts)
		    if(instance.config_claims_worldModes.get(player.getWorld()) == ClaimsMode.Survival && !player.hasPermission("griefprevention.adminclaims") && this.dataStore.claims.size() > 10)
		    {
		        WelcomeTask task = new WelcomeTask(player);
		        Bukkit.getScheduler().scheduleSyncDelayedTask(instance, task, instance.config_claims_manualDeliveryDelaySeconds * 20L);
		    }
		}

		//in case player has changed his name, on successful login, update UUID > Name mapping
		instance.cacheUUIDNamePair(player.getUniqueId(), player.getName());
	}
	
	//when a player quits...
	@EventHandler(priority = EventPriority.HIGHEST)
	void onPlayerQuit(PlayerQuitEvent event)
	{
	    Player player = event.getPlayer();
		UUID playerID = player.getUniqueId();
	    PlayerData playerData = this.dataStore.getPlayerData(playerID);

	    this.dataStore.savePlayerData(player.getUniqueId(), playerData);

        //drop data about this player
        this.dataStore.clearCachedPlayerData(playerID);
	}

	//when a player teleports
	@EventHandler(priority = EventPriority.LOWEST)
	public void onPlayerTeleport(PlayerTeleportEvent event)
	{
	    Player player = event.getPlayer();
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		
		//FEATURE: prevent players from using ender pearls to gain access to secured claims
		TeleportCause cause = event.getCause();
		if(cause == TeleportCause.CHORUS_FRUIT || (cause == TeleportCause.ENDER_PEARL && instance.config_claims_enderPearlsRequireAccessTrust))
		{
			Claim toClaim = this.dataStore.getClaimAt(event.getTo(), false, playerData.lastClaim);
			if(toClaim != null)
			{
				playerData.lastClaim = toClaim;
				String noAccessReason = toClaim.allowAccess(player);
				if(noAccessReason != null)
				{
					instance.sendMessage(player, TextMode.Err, noAccessReason);
					event.setCancelled(true);
					if(cause == TeleportCause.ENDER_PEARL)
					    player.getInventory().addItem(new ItemStack(Material.ENDER_PEARL));
				}
			}
		}
	}
	
	//when a player interacts with a specific part of entity...
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event)
    {
        //treat it the same as interacting with an entity in general
        if(event.getRightClicked().getType() == EntityType.ARMOR_STAND)
        {
            this.onPlayerInteractEntity((PlayerInteractEntityEvent)event);
        }
    }
    
	//when a player interacts with an entity...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerInteractEntity(PlayerInteractEntityEvent event)
	{
		Player player = event.getPlayer();
		Entity entity = event.getRightClicked();
		
		if(!instance.claimsEnabledForWorld(entity.getWorld())) return;
		
		//allow horse protection to be overridden to allow management from other plugins
        if (!instance.config_claims_protectHorses && entity instanceof AbstractHorse ) return;
		if (!instance.config_claims_protectDonkeys && entity instanceof Donkey) return;
		if (!instance.config_claims_protectDonkeys && entity instanceof Mule) return;
		if (!instance.config_claims_protectLlamas && entity instanceof Llama ) return;

        PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
        
        //don't allow interaction with item frames or armor stands in claimed areas without build permission
		if(entity.getType() == EntityType.ARMOR_STAND || entity instanceof Hanging)
		{
			String noBuildReason = instance.allowBuild(player, entity.getLocation(), Material.ITEM_FRAME); 
			if(noBuildReason != null)
			{
				instance.sendMessage(player, TextMode.Err, noBuildReason);
				event.setCancelled(true);
				return;
			}			
		}

		//always allow interactions when player is in ignore claims mode
        if(playerData.ignoreClaims) return;

		//if the entity is a vehicle and we're preventing theft in claims		
		if(instance.config_claims_preventTheft && entity instanceof Vehicle)
		{
			//if the entity is in a claim
			Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, null);
			if(claim != null)
			{
				//for storage entities, apply container rules (this is a potential theft)
				if(entity instanceof InventoryHolder)
				{					
					String noContainersReason = claim.allowContainers(player);
					if(noContainersReason != null)
					{
						instance.sendMessage(player, TextMode.Err, noContainersReason);
						event.setCancelled(true);
						return;
					}
				}
			}
		}
		
		//if the entity is an animal, apply container rules
        if((instance.config_claims_preventTheft && (entity instanceof Animals || entity instanceof Fish)) || (entity.getType() == EntityType.VILLAGER && instance.config_claims_villagerTradingRequiresTrust))
        {
            //if the entity is in a claim
            Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, null);
            if(claim != null)
            {
                if(claim.allowContainers(player) != null)
                {
                    String message = instance.dataStore.getMessage(Messages.NoDamageClaimedEntity, claim.getOwnerName());
                    if(player.hasPermission("griefprevention.ignoreclaims"))
                        message += "  " + instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
					instance.sendMessage(player, TextMode.Err, message);
                    event.setCancelled(true);
                    return;
                }
            }
        }
		
		//if preventing theft, prevent leashing claimed creatures
		if(instance.config_claims_preventTheft && entity instanceof Creature && instance.getItemInHand(player, event.getHand()).getType() == Material.LEAD)
		{
		    Claim claim = this.dataStore.getClaimAt(entity.getLocation(), false, playerData.lastClaim);
            if(claim != null)
            {
                String failureReason = claim.allowContainers(player);
                if(failureReason != null)
                {
                    event.setCancelled(true);
					instance.sendMessage(player, TextMode.Err, failureReason);
                    return;                    
                }
            }
		}
	}
	
	//when a player reels in his fishing rod
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerFish(PlayerFishEvent event)
	{
	    Entity entity = event.getCaught();
	    if(entity == null) return;  //if nothing pulled, uninteresting event
	    
	    //if should be protected from pulling in land claims without permission
	    if(entity.getType() == EntityType.ARMOR_STAND || entity instanceof Animals)
	    {
	        Player player = event.getPlayer();
	        PlayerData playerData = instance.dataStore.getPlayerData(player.getUniqueId());
	        Claim claim = instance.dataStore.getClaimAt(entity.getLocation(), false, playerData.lastClaim);
	        if(claim != null)
	        {
	            //if no permission, cancel
	            String errorMessage = claim.allowContainers(player);
	            if(errorMessage != null)
	            {
	                event.setCancelled(true);
					instance.sendMessage(player, TextMode.Err, Messages.NoDamageClaimedEntity, claim.getOwnerName());
	                return;
	            }
	        }
	    }
	}
	
	//when a player switches in-hand items
	@EventHandler(ignoreCancelled = true)
	public void onItemHeldChange(PlayerItemHeldEvent event)
	{
		Player player = event.getPlayer();
		
		//if he's switching to the golden shovel
		int newSlot = event.getNewSlot();
		ItemStack newItemStack = player.getInventory().getItem(newSlot);
		if(newItemStack != null && newItemStack.getType() == instance.config_claims_modificationTool)
		{
			//give the player his available claim blocks count and claiming instructions, but only if he keeps the shovel equipped for a minimum time, to avoid mouse wheel spam
			if(instance.claimsEnabledForWorld(player.getWorld()))
			{
				EquipShovelProcessingTask task = new EquipShovelProcessingTask(player);
				instance.getServer().getScheduler().scheduleSyncDelayedTask(instance, task, 15L);  //15L is approx. 3/4 of a second
			}
		}
	}
	
	//block use of buckets within other players' claims
	private HashSet<Material> commonAdjacentBlocks_water = new HashSet<Material>(Arrays.asList(Material.WATER, Material.FARMLAND, Material.DIRT, Material.STONE));
	private HashSet<Material> commonAdjacentBlocks_lava = new HashSet<Material>(Arrays.asList(Material.LAVA, Material.DIRT, Material.STONE));
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerBucketEmpty (PlayerBucketEmptyEvent bucketEvent)
	{
		if(!instance.claimsEnabledForWorld(bucketEvent.getBlockClicked().getWorld())) return;
	    
	    Player player = bucketEvent.getPlayer();
		Block block = bucketEvent.getBlockClicked().getRelative(bucketEvent.getBlockFace());
		int minLavaDistance = 10;
		
		//make sure the player is allowed to build at the location
		String noBuildReason = instance.allowBuild(player, block.getLocation(), Material.WATER);
		if(noBuildReason != null)
		{
			instance.sendMessage(player, TextMode.Err, noBuildReason);
			bucketEvent.setCancelled(true);
			return;
		}
		
		//if the bucket is being used in a claim, allow for dumping lava closer to other players
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		Claim claim = this.dataStore.getClaimAt(block.getLocation(), false, playerData.lastClaim);
		if(claim != null)
		{
			minLavaDistance = 3;
		}

		//log any suspicious placements (check sea level, world type, and adjacent blocks)
		if(block.getY() >= instance.getSeaLevel(block.getWorld()) - 5 && !player.hasPermission("griefprevention.lava") && block.getWorld().getEnvironment() != Environment.NETHER)
		{
		    //if certain blocks are nearby, it's less suspicious and not worth logging
		    HashSet<Material> exclusionAdjacentTypes;
		    if(bucketEvent.getBucket() == Material.WATER_BUCKET)
		        exclusionAdjacentTypes = this.commonAdjacentBlocks_water;
		    else
		        exclusionAdjacentTypes = this.commonAdjacentBlocks_lava;
		    
		    boolean makeLogEntry = true;
		    BlockFace [] adjacentDirections = new BlockFace[] {BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.DOWN};
		    for(BlockFace direction : adjacentDirections)
		    {
		        Material adjacentBlockType = block.getRelative(direction).getType();
		        if(exclusionAdjacentTypes.contains(adjacentBlockType))
	            {
		            makeLogEntry = false;
		            break;
	            }
		    }
		    
		    if(makeLogEntry)
	        {
	            instance.AddLogEntry(player.getName() + " placed suspicious " + bucketEvent.getBucket().name() + " @ " + instance.getfriendlyLocationString(block.getLocation()), CustomLogEntryTypes.SuspiciousActivity);
	        }
		}
	}
	
	//see above
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPlayerBucketFill (PlayerBucketFillEvent bucketEvent)
	{
		Player player = bucketEvent.getPlayer();
		Block block = bucketEvent.getBlockClicked();
		
		if(!instance.claimsEnabledForWorld(block.getWorld())) return;
		
		//make sure the player is allowed to build at the location
		String noBuildReason = instance.allowBuild(player, block.getLocation(), Material.AIR);
		if(noBuildReason != null)
		{
		    //exemption for cow milking (permissions will be handled by player interact with entity event instead)
		    Material blockType = block.getType();
		    if (blockType == Material.AIR)
		    	return;
		    if(blockType.isSolid())
		    {
				BlockData blockData = block.getBlockData();
				if (!(blockData instanceof Waterlogged) || !((Waterlogged)blockData).isWaterlogged())
					return;
			}
		    
			instance.sendMessage(player, TextMode.Err, noBuildReason);
			bucketEvent.setCancelled(true);
			return;
		}
	}
	
	//when a player interacts with the world
    @EventHandler(priority = EventPriority.LOWEST)
	void onPlayerInteract(PlayerInteractEvent event)
	{
	    //not interested in left-click-on-air actions
	    Action action = event.getAction();
	    if(action == Action.LEFT_CLICK_AIR) return;
	    
	    Player player = event.getPlayer();
		Block clickedBlock = event.getClickedBlock(); //null returned here means interacting with air
		
		Material clickedBlockType = null;
		if(clickedBlock != null)
		{
		    clickedBlockType = clickedBlock.getType();
		}
		else
		{
		    clickedBlockType = Material.AIR;
		}

		PlayerData playerData = null;

		//Turtle eggs
		if(action == Action.PHYSICAL)
		{
			if (clickedBlockType != Material.TURTLE_EGG)
				return;
			playerData = this.dataStore.getPlayerData(player.getUniqueId());
			Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
			if(claim != null)
			{
				playerData.lastClaim = claim;

				String noAccessReason = claim.allowBreak(player, clickedBlockType);
				if(noAccessReason != null)
				{
					event.setCancelled(true);
					return;
				}
			}
			return;
		}
		
		//don't care about left-clicking on most blocks, this is probably a break action
                if(action == Action.LEFT_CLICK_BLOCK && clickedBlock != null)
                {
                        if(clickedBlock.getY() < clickedBlock.getWorld().getMaxHeight() - 1 || event.getBlockFace() != BlockFace.UP)
                        {
                            Block adjacentBlock = clickedBlock.getRelative(event.getBlockFace());
                            byte lightLevel = adjacentBlock.getLightFromBlocks();
                            if(lightLevel == 15 && adjacentBlock.getType() == Material.FIRE)
                            {
                                if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
                                Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
                                if(claim != null)
                                {
                                    playerData.lastClaim = claim;

                                    String noBuildReason = claim.allowBuild(player, Material.AIR);
                                    if(noBuildReason != null)
                                    {
                                        event.setCancelled(true);
                                        instance.sendMessage(player, TextMode.Err, noBuildReason);
                                        player.sendBlockChange(adjacentBlock.getLocation(), adjacentBlock.getType(), adjacentBlock.getData());
                                        return;
                                    }
                                }
                            }
                        }

                        //exception for blocks on a specific watch list
                        if(!this.onLeftClickWatchList(clickedBlockType))
                        {
                            return;
                        }
                }
        
		//apply rules for containers and crafting blocks
		if(	clickedBlock != null && instance.config_claims_preventTheft && (
						event.getAction() == Action.RIGHT_CLICK_BLOCK && (
						(this.isInventoryHolder(clickedBlock) && clickedBlock.getType() != Material.LECTERN) ||
						clickedBlockType == Material.CAULDRON ||
						clickedBlockType == Material.JUKEBOX ||
						clickedBlockType == Material.ANVIL ||
						clickedBlockType == Material.CHIPPED_ANVIL ||
						clickedBlockType == Material.DAMAGED_ANVIL ||
						clickedBlockType == Material.CAKE ||
						clickedBlockType == Material.SWEET_BERRY_BUSH)))
		{			
			if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());

			// check permissions for the claim the player is in
			Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
			if(claim != null)
			{
				playerData.lastClaim = claim;

				String noContainersReason = claim.allowContainers(player);
				if(noContainersReason != null)
				{
					event.setCancelled(true);
					instance.sendMessage(player, TextMode.Err, noContainersReason);
					return;
				}
			}
		}
		
		//otherwise apply rules for doors and beds, if configured that way
		else if( clickedBlock != null && 
		        
		(instance.config_claims_lockWoodenDoors && (
				clickedBlockType == Material.OAK_DOOR   ||
				clickedBlockType == Material.ACACIA_DOOR   || 
				clickedBlockType == Material.BIRCH_DOOR    ||
				clickedBlockType == Material.JUNGLE_DOOR   ||
				clickedBlockType == Material.SPRUCE_DOOR   ||
				clickedBlockType == Material.DARK_OAK_DOOR)) ||
		        
                (instance.config_claims_preventButtonsSwitches && (	clickedBlockType == Material.WHITE_BED		||
									clickedBlockType == Material.ORANGE_BED		||
									clickedBlockType == Material.MAGENTA_BED	||
									clickedBlockType == Material.LIGHT_BLUE_BED	||
									clickedBlockType == Material.YELLOW_BED		||
									clickedBlockType == Material.LIME_BED		||
									clickedBlockType == Material.PINK_BED		||
									clickedBlockType == Material.GRAY_BED		||
									clickedBlockType == Material.LIGHT_GRAY_BED	||
									clickedBlockType == Material.CYAN_BED		||
									clickedBlockType == Material.PURPLE_BED		||
									clickedBlockType == Material.BLUE_BED		||
									clickedBlockType == Material.BROWN_BED		||
									clickedBlockType == Material.GREEN_BED		||
									clickedBlockType == Material.RED_BED		||
									clickedBlockType == Material.BLACK_BED)) ||
		        
                (instance.config_claims_lockTrapDoors && (
					clickedBlockType == Material.OAK_TRAPDOOR ||
					clickedBlockType == Material.SPRUCE_TRAPDOOR ||
					clickedBlockType == Material.BIRCH_TRAPDOOR ||
					clickedBlockType == Material.JUNGLE_TRAPDOOR ||
					clickedBlockType == Material.ACACIA_TRAPDOOR ||
					clickedBlockType == Material.DARK_OAK_TRAPDOOR)) ||
				
                (instance.config_claims_lockFenceGates && (
				clickedBlockType == Material.OAK_FENCE_GATE      ||
				clickedBlockType == Material.ACACIA_FENCE_GATE   || 
				clickedBlockType == Material.BIRCH_FENCE_GATE    ||
				clickedBlockType == Material.JUNGLE_FENCE_GATE   ||
				clickedBlockType == Material.SPRUCE_FENCE_GATE   ||
				clickedBlockType == Material.DARK_OAK_FENCE_GATE)) ||
				(instance.config_claims_lecternReadingRequiresAccessTrust && clickedBlockType == Material.LECTERN))
		{
		    if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
		    Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
			if(claim != null)
			{
				playerData.lastClaim = claim;

				String noAccessReason = claim.allowAccess(player);
				if(noAccessReason != null)
				{
					event.setCancelled(true);
					instance.sendMessage(player, TextMode.Err, noAccessReason);
					return;
				}
			}
		}
		
		//otherwise apply rules for buttons and switches
		else if(clickedBlock != null && instance.config_claims_preventButtonsSwitches && (clickedBlockType == null || clickedBlockType == Material.STONE_BUTTON || Tag.BUTTONS.isTagged(clickedBlockType) || clickedBlockType == Material.LEVER))
		{
		    if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
		    Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
			if(claim != null)
			{
			    playerData.lastClaim = claim;
				
				String noAccessReason = claim.allowAccess(player);
				if(noAccessReason != null)
				{
					event.setCancelled(true);
					instance.sendMessage(player, TextMode.Err, noAccessReason);
					return;
				}
			}			
		}
		
		//otherwise apply rule for cake
        else if(clickedBlock != null && instance.config_claims_preventTheft && clickedBlockType == Material.CAKE)
        {
            if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
            Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
            if(claim != null)
            {
                playerData.lastClaim = claim;
                
                String noContainerReason = claim.allowAccess(player);
                if(noContainerReason != null)
                {
                    event.setCancelled(true);
                    instance.sendMessage(player, TextMode.Err, noContainerReason);
                    return;
                }
            }           
        }
		
		//apply rule for note blocks and repeaters and daylight sensors //RoboMWM: Include flower pots
		else if(clickedBlock != null && 
			(
		                clickedBlockType == Material.NOTE_BLOCK || 
		                clickedBlockType == Material.REPEATER || 
		                clickedBlockType == Material.DRAGON_EGG ||
		                clickedBlockType == Material.DAYLIGHT_DETECTOR ||
		                clickedBlockType == Material.COMPARATOR ||
						Tag.FLOWER_POTS.isTagged(clickedBlockType)
		        ))
		{
		    if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
		    Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
			if(claim != null)
			{
				String noBuildReason = claim.allowBuild(player, clickedBlockType);
				if(noBuildReason != null)
				{
					event.setCancelled(true);
					instance.sendMessage(player, TextMode.Err, noBuildReason);
					return;
				}
			}
		}
		
		//otherwise handle right click (shovel, string, bonemeal) //RoboMWM: flint and steel
		else
		{
			//ignore all actions except right-click on a block or in the air
			if(action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;
			
			//what's the player holding?
			EquipmentSlot hand = event.getHand();
			ItemStack itemInHand = instance.getItemInHand(player, hand);
			Material materialInHand = itemInHand.getType();	
			
			Set<Material> dyes = new HashSet<>();
			
			for (Material material : Material.values())
			{
				if (!material.isLegacy() && material.name().endsWith("_DYE"))
					dyes.add(material);
			}

			
			//if it's bonemeal, armor stand, spawn egg, etc - check for build permission //RoboMWM: also check flint and steel to stop TNT ignition
			if(clickedBlock != null && (materialInHand == Material.BONE_MEAL
					|| materialInHand == Material.ARMOR_STAND
					|| materialInHand == Material.END_CRYSTAL
					|| materialInHand == Material.FLINT_AND_STEEL
					|| dyes.contains(materialInHand)))
			{
				String noBuildReason = instance
						.allowBuild(player, clickedBlock
								.getLocation(),
								clickedBlockType);
				if(noBuildReason != null)
				{
					instance.sendMessage(player, TextMode.Err, noBuildReason);
					event.setCancelled(true);
				}
				
				return;
			}
			
			else if(clickedBlock != null && (
			        materialInHand == Material.OAK_BOAT		|| 
			        materialInHand == Material.SPRUCE_BOAT		|| 
			        materialInHand == Material.BIRCH_BOAT		|| 
			        materialInHand == Material.JUNGLE_BOAT		|| 
			        materialInHand == Material.ACACIA_BOAT		||
			        materialInHand == Material.DARK_OAK_BOAT))
			{
			    if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
			    Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
				if(claim != null)
				{
					String noBuildReason = claim.allowBuild(player, Material.OAK_BOAT); // Though only checks OAK_BOAT, permission should be same for all boats. Plus it being a boat doesn't seem to make a difference currently.
					if(noBuildReason != null)
					{
						instance.sendMessage(player, TextMode.Err, noBuildReason);
						event.setCancelled(true);
					}
				}
				
				return;
			}
			
			//survival world minecart placement requires container trust, which is the permission required to remove the minecart later
			else if(clickedBlock != null &&
				(materialInHand == Material.MINECART				|| 
				materialInHand == Material.FURNACE_MINECART			|| 
				materialInHand == Material.CHEST_MINECART			|| 
				materialInHand == Material.TNT_MINECART				|| 
				materialInHand == Material.HOPPER_MINECART))
			{
				if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
				Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
				if(claim != null)
				{
					String reason = claim.allowContainers(player);
					if(reason != null)
					{
						instance.sendMessage(player, TextMode.Err, reason);
						event.setCancelled(true);
					}
				}

				return;
			}

			//if he's investigating a claim
			else if(materialInHand == instance.config_claims_investigationTool &&  hand == EquipmentSlot.HAND)
			{
		        //if claims are disabled in this world, do nothing
			    if(!instance.claimsEnabledForWorld(player.getWorld())) return;

			    //if holding shift (sneaking), show all claims in area
			    if(player.isSneaking() && player.hasPermission("griefprevention.visualizenearbyclaims"))
			    {
			        //find nearby claims
			        Set<Claim> claims = this.dataStore.getNearbyClaims(player.getLocation());

                    // alert plugins of a visualization
                    Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, claims));

			        //visualize boundaries
                    Visualization visualization = Visualization.fromClaims(claims, player.getEyeLocation().getBlockY(), VisualizationType.Claim, player.getLocation());
                    Visualization.Apply(player, visualization);

                    instance.sendMessage(player, TextMode.Info, Messages.ShowNearbyClaims, String.valueOf(claims.size()));

                    return;
			    }

			    //FEATURE: shovel and stick can be used from a distance away
		        if(action == Action.RIGHT_CLICK_AIR)
		        {
		            //try to find a far away non-air block along line of sight
		            clickedBlock = getTargetBlock(player, 100);
		            clickedBlockType = clickedBlock.getType();
		        }

		        //if no block, stop here
		        if(clickedBlock == null)
		        {
		            return;
		        }

			    //air indicates too far away
				if(clickedBlockType == Material.AIR)
				{
					instance.sendMessage(player, TextMode.Err, Messages.TooFarAway);

                    // alert plugins of a visualization
                    Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, Collections.<Claim>emptySet()));

					Visualization.Revert(player);
					return;
				}

				if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());
				Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false /*ignore height*/, playerData.lastClaim);

				//no claim case
				if(claim == null)
				{
					instance.sendMessage(player, TextMode.Info, Messages.BlockNotClaimed);

                    // alert plugins of a visualization
                    Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, Collections.<Claim>emptySet()));

					Visualization.Revert(player);
				}

				//claim case
				else
				{
					playerData.lastClaim = claim;
					instance.sendMessage(player, TextMode.Info, Messages.BlockClaimed, claim.getOwnerName());

					//visualize boundary
					Visualization visualization = Visualization.FromClaim(claim, player.getEyeLocation().getBlockY(), VisualizationType.Claim, player.getLocation());

                    // alert plugins of a visualization
                    Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, claim));

					Visualization.Apply(player, visualization);

					if (player.hasPermission("griefprevention.seeclaimsize")) {
						instance.sendMessage(player, TextMode.Info, "  " + claim.getWidth() + "x" + claim.getHeight() + "=" + claim.getArea());
					}

					//if permission, tell about the player's offline time
					if(!claim.isAdminClaim() && (player.hasPermission("griefprevention.deleteclaims") || player.hasPermission("griefprevention.seeinactivity")))
					{
						if(claim.parent != null)
						{
						    claim = claim.parent;
						}
						Date lastLogin = new Date(Bukkit.getOfflinePlayer(claim.ownerID).getLastPlayed());
						Date now = new Date();
						long daysElapsed = (now.getTime() - lastLogin.getTime()) / (1000 * 60 * 60 * 24);

						instance.sendMessage(player, TextMode.Info, Messages.PlayerOfflineTime, String.valueOf(daysElapsed));

						//drop the data we just loaded, if the player isn't online
						if(instance.getServer().getPlayer(claim.ownerID) == null)
							this.dataStore.clearCachedPlayerData(claim.ownerID);
					}
				}

				return;
			}

			//if it's a golden shovel
			else if(materialInHand != instance.config_claims_modificationTool || hand != EquipmentSlot.HAND) return;

			event.setCancelled(true);  //GriefPrevention exclusively reserves this tool  (e.g. no grass path creation for golden shovel)

			//disable golden shovel while under siege
			if(playerData == null) playerData = this.dataStore.getPlayerData(player.getUniqueId());

			//FEATURE: shovel and stick can be used from a distance away
            if(action == Action.RIGHT_CLICK_AIR)
            {
                //try to find a far away non-air block along line of sight
                clickedBlock = getTargetBlock(player, 100);
                clickedBlockType = clickedBlock.getType();
            }

            //if no block, stop here
            if(clickedBlock == null)
            {
                return;
            }

			//can't use the shovel from too far away
			if(clickedBlockType == Material.AIR)
			{
				instance.sendMessage(player, TextMode.Err, Messages.TooFarAway);
				return;
			}

			//if the player is in restore nature mode, do only that
			UUID playerID = player.getUniqueId();
			playerData = this.dataStore.getPlayerData(player.getUniqueId());
			if(playerData.shovelMode == ShovelMode.RestoreNature || playerData.shovelMode == ShovelMode.RestoreNatureAggressive)
			{
				//if the clicked block is in a claim, visualize that claim and deliver an error message
				Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), false, playerData.lastClaim);
				if(claim != null)
				{
					instance.sendMessage(player, TextMode.Err, Messages.BlockClaimed, claim.getOwnerName());
					Visualization visualization = Visualization.FromClaim(claim, clickedBlock.getY(), VisualizationType.ErrorClaim, player.getLocation());

                    // alert plugins of a visualization
                    Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, claim));

					Visualization.Apply(player, visualization);

					return;
				}

				//figure out which chunk to repair
				Chunk chunk = player.getWorld().getChunkAt(clickedBlock.getLocation());
				//start the repair process

				//set boundaries for processing
				int miny = clickedBlock.getY();

				//if not in aggressive mode, extend the selection down to a little below sea level
				if(!(playerData.shovelMode == ShovelMode.RestoreNatureAggressive))
				{
					if(miny > instance.getSeaLevel(chunk.getWorld()) - 10)
					{
						miny = instance.getSeaLevel(chunk.getWorld()) - 10;
					}
				}

				instance.restoreChunk(chunk, miny, playerData.shovelMode == ShovelMode.RestoreNatureAggressive, 0, player);

				return;
			}

			//if in restore nature fill mode
			if(playerData.shovelMode == ShovelMode.RestoreNatureFill)
			{
				ArrayList<Material> allowedFillBlocks = new ArrayList<Material>();
				Environment environment = clickedBlock.getWorld().getEnvironment();
				if(environment == Environment.NETHER)
				{
					allowedFillBlocks.add(Material.NETHERRACK);
				}
				else if(environment == Environment.THE_END)
				{
					allowedFillBlocks.add(Material.END_STONE);
				}
				else
				{
					allowedFillBlocks.add(Material.GRASS);
					allowedFillBlocks.add(Material.DIRT);
					allowedFillBlocks.add(Material.STONE);
					allowedFillBlocks.add(Material.SAND);
					allowedFillBlocks.add(Material.SANDSTONE);
					allowedFillBlocks.add(Material.ICE);
				}

				Block centerBlock = clickedBlock;

				int maxHeight = centerBlock.getY();
				int minx = centerBlock.getX() - playerData.fillRadius;
				int maxx = centerBlock.getX() + playerData.fillRadius;
				int minz = centerBlock.getZ() - playerData.fillRadius;
				int maxz = centerBlock.getZ() + playerData.fillRadius;
				int minHeight = maxHeight - 10;
				if(minHeight < 0) minHeight = 0;

				Claim cachedClaim = null;
				for(int x = minx; x <= maxx; x++)
				{
					for(int z = minz; z <= maxz; z++)
					{
						//circular brush
						Location location = new Location(centerBlock.getWorld(), x, centerBlock.getY(), z);
						if(location.distance(centerBlock.getLocation()) > playerData.fillRadius) continue;

						//default fill block is initially the first from the allowed fill blocks list above
						Material defaultFiller = allowedFillBlocks.get(0);

						//prefer to use the block the player clicked on, if it's an acceptable fill block
						if(allowedFillBlocks.contains(centerBlock.getType()))
						{
							defaultFiller = centerBlock.getType();
						}

						//if the player clicks on water, try to sink through the water to find something underneath that's useful for a filler
						else if(centerBlock.getType() == Material.WATER)
						{
							Block block = centerBlock.getWorld().getBlockAt(centerBlock.getLocation());
							while(!allowedFillBlocks.contains(block.getType()) && block.getY() > centerBlock.getY() - 10)
							{
								block = block.getRelative(BlockFace.DOWN);
							}
							if(allowedFillBlocks.contains(block.getType()))
							{
								defaultFiller = block.getType();
							}
						}

						//fill bottom to top
						for(int y = minHeight; y <= maxHeight; y++)
						{
							Block block = centerBlock.getWorld().getBlockAt(x, y, z);

							//respect claims
							Claim claim = this.dataStore.getClaimAt(block.getLocation(), false, cachedClaim);
							if(claim != null)
							{
								cachedClaim = claim;
								break;
							}

							//only replace air, spilling water, snow, long grass
							if(block.getType() == Material.AIR || block.getType() == Material.SNOW || (block.getType() == Material.WATER && ((Levelled) block.getBlockData()).getLevel() != 0) || block.getType() == Material.GRASS)
							{
								//if the top level, always use the default filler picked above
								if(y == maxHeight)
								{
									block.setType(defaultFiller);
								}

								//otherwise look to neighbors for an appropriate fill block
								else
								{
									Block eastBlock = block.getRelative(BlockFace.EAST);
									Block westBlock = block.getRelative(BlockFace.WEST);
									Block northBlock = block.getRelative(BlockFace.NORTH);
									Block southBlock = block.getRelative(BlockFace.SOUTH);

									//first, check lateral neighbors (ideally, want to keep natural layers)
									if(allowedFillBlocks.contains(eastBlock.getType()))
									{
										block.setType(eastBlock.getType());
									}
									else if(allowedFillBlocks.contains(westBlock.getType()))
									{
										block.setType(westBlock.getType());
									}
									else if(allowedFillBlocks.contains(northBlock.getType()))
									{
										block.setType(northBlock.getType());
									}
									else if(allowedFillBlocks.contains(southBlock.getType()))
									{
										block.setType(southBlock.getType());
									}

									//if all else fails, use the default filler selected above
									else
									{
										block.setType(defaultFiller);
									}
								}
							}
						}
					}
				}

				return;
			}

			//if the player doesn't have claims permission, don't do anything
			if(!player.hasPermission("griefprevention.createclaims"))
			{
				instance.sendMessage(player, TextMode.Err, Messages.NoCreateClaimPermission);
				return;
			}

			//if he's resizing a claim and that claim hasn't been deleted since he started resizing it
			if(playerData.claimResizing != null && playerData.claimResizing.inDataStore)
			{
				if(clickedBlock.getLocation().equals(playerData.lastShovelLocation)) return;

				//figure out what the coords of his new claim would be
				int newx1, newx2, newz1, newz2, newy1, newy2;
				if(playerData.lastShovelLocation.getBlockX() == playerData.claimResizing.getLesserBoundaryCorner().getBlockX())
				{
					newx1 = clickedBlock.getX();
				}
				else
				{
					newx1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockX();
				}

				if(playerData.lastShovelLocation.getBlockX() == playerData.claimResizing.getGreaterBoundaryCorner().getBlockX())
				{
					newx2 = clickedBlock.getX();
				}
				else
				{
					newx2 = playerData.claimResizing.getGreaterBoundaryCorner().getBlockX();
				}

				if(playerData.lastShovelLocation.getBlockZ() == playerData.claimResizing.getLesserBoundaryCorner().getBlockZ())
				{
					newz1 = clickedBlock.getZ();
				}
				else
				{
					newz1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockZ();
				}

				if(playerData.lastShovelLocation.getBlockZ() == playerData.claimResizing.getGreaterBoundaryCorner().getBlockZ())
				{
					newz2 = clickedBlock.getZ();
				}
				else
				{
					newz2 = playerData.claimResizing.getGreaterBoundaryCorner().getBlockZ();
				}

				newy1 = playerData.claimResizing.getLesserBoundaryCorner().getBlockY();
				newy2 = clickedBlock.getY() - instance.config_claims_claimsExtendIntoGroundDistance;

				this.dataStore.resizeClaimWithChecks(player, playerData, newx1, newx2, newy1, newy2, newz1, newz2);

				return;
			}

			//otherwise, since not currently resizing a claim, must be starting a resize, creating a new claim, or creating a subdivision
			Claim claim = this.dataStore.getClaimAt(clickedBlock.getLocation(), true /*ignore height*/, playerData.lastClaim);

			//if within an existing claim, he's not creating a new one
			if(claim != null)
			{
				//if the player has permission to edit the claim or subdivision
				String noEditReason = claim.allowEdit(player);
				if(noEditReason == null)
				{
					//if he clicked on a corner, start resizing it
					if((clickedBlock.getX() == claim.getLesserBoundaryCorner().getBlockX() || clickedBlock.getX() == claim.getGreaterBoundaryCorner().getBlockX()) && (clickedBlock.getZ() == claim.getLesserBoundaryCorner().getBlockZ() || clickedBlock.getZ() == claim.getGreaterBoundaryCorner().getBlockZ()))
					{
						playerData.claimResizing = claim;
						playerData.lastShovelLocation = clickedBlock.getLocation();
						instance.sendMessage(player, TextMode.Instr, Messages.ResizeStart);
					}

					//if he didn't click on a corner and is in subdivision mode, he's creating a new subdivision
					else if(playerData.shovelMode == ShovelMode.Subdivide)
					{
						//if it's the first click, he's trying to start a new subdivision
						if(playerData.lastShovelLocation == null)
						{
							//if the clicked claim was a subdivision, tell him he can't start a new subdivision here
							if(claim.parent != null)
							{
								instance.sendMessage(player, TextMode.Err, Messages.ResizeFailOverlapSubdivision);
							}

							//otherwise start a new subdivision
							else
							{
								instance.sendMessage(player, TextMode.Instr, Messages.SubdivisionStart);
								playerData.lastShovelLocation = clickedBlock.getLocation();
								playerData.claimSubdividing = claim;
							}
						}

						//otherwise, he's trying to finish creating a subdivision by setting the other boundary corner
						else
						{
							//if last shovel location was in a different world, assume the player is starting the create-claim workflow over
							if(!playerData.lastShovelLocation.getWorld().equals(clickedBlock.getWorld()))
							{
								playerData.lastShovelLocation = null;
								this.onPlayerInteract(event);
								return;
							}

							//try to create a new claim (will return null if this subdivision overlaps another)
							CreateClaimResult result = this.dataStore.createClaim(
									player.getWorld(),
									playerData.lastShovelLocation.getBlockX(), clickedBlock.getX(),
									playerData.lastShovelLocation.getBlockY() - instance.config_claims_claimsExtendIntoGroundDistance, clickedBlock.getY() - instance.config_claims_claimsExtendIntoGroundDistance,
									playerData.lastShovelLocation.getBlockZ(), clickedBlock.getZ(),
									null,  //owner is not used for subdivisions
									playerData.claimSubdividing,
									null, player);

							//if it didn't succeed, tell the player why
							if(!result.succeeded)
							{
								instance.sendMessage(player, TextMode.Err, Messages.CreateSubdivisionOverlap);

								Visualization visualization = Visualization.FromClaim(result.claim, clickedBlock.getY(), VisualizationType.ErrorClaim, player.getLocation());

                                // alert plugins of a visualization
                                Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, result.claim));

								Visualization.Apply(player, visualization);

								return;
							}

							//otherwise, advise him on the /trust command and show him his new subdivision
							else
							{
								instance.sendMessage(player, TextMode.Success, Messages.SubdivisionSuccess);
								Visualization visualization = Visualization.FromClaim(result.claim, clickedBlock.getY(), VisualizationType.Claim, player.getLocation());

                                // alert plugins of a visualization
                                Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, result.claim));

								Visualization.Apply(player, visualization);
								playerData.lastShovelLocation = null;
								playerData.claimSubdividing = null;
							}
						}
					}

					//otherwise tell him he can't create a claim here, and show him the existing claim
					//also advise him to consider /abandonclaim or resizing the existing claim
					else
					{
						instance.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlap);
						Visualization visualization = Visualization.FromClaim(claim, clickedBlock.getY(), VisualizationType.Claim, player.getLocation());

                        // alert plugins of a visualization
                        Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, claim));

						Visualization.Apply(player, visualization);
					}
				}

				//otherwise tell the player he can't claim here because it's someone else's claim, and show him the claim
				else
				{
					instance.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapOtherPlayer, claim.getOwnerName());
					Visualization visualization = Visualization.FromClaim(claim, clickedBlock.getY(), VisualizationType.ErrorClaim, player.getLocation());

                    // alert plugins of a visualization
                    Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, claim));

					Visualization.Apply(player, visualization);
				}

				return;
			}

			//otherwise, the player isn't in an existing claim!

			//if he hasn't already start a claim with a previous shovel action
			Location lastShovelLocation = playerData.lastShovelLocation;
			if(lastShovelLocation == null)
			{
				//if claims are not enabled in this world and it's not an administrative claim, display an error message and stop
				if(!instance.claimsEnabledForWorld(player.getWorld()))
				{
					instance.sendMessage(player, TextMode.Err, Messages.ClaimsDisabledWorld);
					return;
				}

				//if he's at the claim count per player limit already and doesn't have permission to bypass, display an error message
				if(instance.config_claims_maxClaimsPerPlayer > 0 &&
				   !player.hasPermission("griefprevention.overrideclaimcountlimit") &&
				   playerData.getClaims().size() >= instance.config_claims_maxClaimsPerPlayer)
				{
				    instance.sendMessage(player, TextMode.Err, Messages.ClaimCreationFailedOverClaimCountLimit);
				    return;
				}

				//remember it, and start him on the new claim
				playerData.lastShovelLocation = clickedBlock.getLocation();
				instance.sendMessage(player, TextMode.Instr, Messages.ClaimStart);

				//show him where he's working
                Claim newClaim = new Claim(clickedBlock.getLocation(), clickedBlock.getLocation(), null, new ArrayList<String>(), new ArrayList<String>(), new ArrayList<String>(), new ArrayList<String>(), null);
				Visualization visualization = Visualization.FromClaim(newClaim, clickedBlock.getY(), VisualizationType.RestoreNature, player.getLocation());

                // alert plugins of a visualization
                Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, newClaim));

				Visualization.Apply(player, visualization);
			}

			//otherwise, he's trying to finish creating a claim by setting the other boundary corner
			else
			{
				//if last shovel location was in a different world, assume the player is starting the create-claim workflow over
				if(!lastShovelLocation.getWorld().equals(clickedBlock.getWorld()))
				{
					playerData.lastShovelLocation = null;
					this.onPlayerInteract(event);
					return;
				}

				//apply minimum claim dimensions rule
				int newClaimWidth = Math.abs(playerData.lastShovelLocation.getBlockX() - clickedBlock.getX()) + 1;
				int newClaimHeight = Math.abs(playerData.lastShovelLocation.getBlockZ() - clickedBlock.getZ()) + 1;

				if(playerData.shovelMode != ShovelMode.Admin)
				{
				    if(newClaimWidth < instance.config_claims_minWidth || newClaimHeight < instance.config_claims_minWidth)
				    {
    					//this IF block is a workaround for craftbukkit bug which fires two events for one interaction
    				    if(newClaimWidth != 1 && newClaimHeight != 1)
    				    {
    				        instance.sendMessage(player, TextMode.Err, Messages.NewClaimTooNarrow, String.valueOf(instance.config_claims_minWidth));
    				    }
    				    return;
				    }

					int newArea = newClaimWidth * newClaimHeight;
                    if(newArea < instance.config_claims_minArea)
                    {
                        if(newArea != 1)
                        {
                            instance.sendMessage(player, TextMode.Err, Messages.ResizeClaimInsufficientArea, String.valueOf(instance.config_claims_minArea));
                        }

                        return;
                    }
				}

				//if not an administrative claim, verify the player has enough claim blocks for this new claim
				if(playerData.shovelMode != ShovelMode.Admin)
				{
					int newClaimArea = newClaimWidth * newClaimHeight;
					int remainingBlocks = playerData.getRemainingClaimBlocks();
					if(newClaimArea > remainingBlocks)
					{
						instance.sendMessage(player, TextMode.Err, Messages.CreateClaimInsufficientBlocks, String.valueOf(newClaimArea - remainingBlocks));
						instance.dataStore.tryAdvertiseAdminAlternatives(player);
						return;
					}
				}
				else
				{
					playerID = null;
				}

				//try to create a new claim
				CreateClaimResult result = this.dataStore.createClaim(
						player.getWorld(),
						lastShovelLocation.getBlockX(), clickedBlock.getX(),
						lastShovelLocation.getBlockY() - instance.config_claims_claimsExtendIntoGroundDistance, clickedBlock.getY() - instance.config_claims_claimsExtendIntoGroundDistance,
						lastShovelLocation.getBlockZ(), clickedBlock.getZ(),
						playerID,
						null, null,
						player);

				//if it didn't succeed, tell the player why
				if(!result.succeeded)
				{
					if(result.claim != null)
					{
    				    instance.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapShort);

    					Visualization visualization = Visualization.FromClaim(result.claim, clickedBlock.getY(), VisualizationType.ErrorClaim, player.getLocation());

                        // alert plugins of a visualization
                        Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, result.claim));

    					Visualization.Apply(player, visualization);
					}
					else
					{
					    instance.sendMessage(player, TextMode.Err, Messages.CreateClaimFailOverlapRegion);
					}

					return;
				}

				//otherwise, advise him on the /trust command and show him his new claim
				else
				{
					instance.sendMessage(player, TextMode.Success, Messages.CreateClaimSuccess);
					Visualization visualization = Visualization.FromClaim(result.claim, clickedBlock.getY(), VisualizationType.Claim, player.getLocation());

                    // alert plugins of a visualization
                    Bukkit.getPluginManager().callEvent(new VisualizationEvent(player, result.claim));

					Visualization.Apply(player, visualization);
					playerData.lastShovelLocation = null;

					//if it's a big claim, tell the player about subdivisions
					if(!player.hasPermission("griefprevention.adminclaims") && result.claim.getArea() >= 1000)
		            {
		                instance.sendMessage(player, TextMode.Info, Messages.BecomeMayor, 200L);
		                instance.sendMessage(player, TextMode.Instr, Messages.SubdivisionVideo2, 201L, DataStore.SUBDIVISION_VIDEO_URL);
		            }

					instance.autoExtendClaim(result.claim);
				}
			}
		}
	}

	// Stops an untrusted player from removing a book from a lectern
	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	void onTakeBook(PlayerTakeLecternBookEvent event)
	{
		Player player = event.getPlayer();
		PlayerData playerData = this.dataStore.getPlayerData(player.getUniqueId());
		Claim claim = this.dataStore.getClaimAt(event.getLectern().getLocation(), false, playerData.lastClaim);
		if (claim != null)
		{
			playerData.lastClaim = claim;
			String noContainerReason = claim.allowContainers(player);
			if (noContainerReason != null)
			{
				event.setCancelled(true);
				player.closeInventory();
				GriefPrevention.sendMessage(player, TextMode.Err, noContainerReason);
			}
		}
	}
	
    //determines whether a block type is an inventory holder.  uses a caching strategy to save cpu time
	private ConcurrentHashMap<Material, Boolean> inventoryHolderCache = new ConcurrentHashMap<Material, Boolean>();
	private boolean isInventoryHolder(Block clickedBlock)
	{
	    @SuppressWarnings("deprecation")
        Material cacheKey = clickedBlock.getType();
	    Boolean cachedValue = this.inventoryHolderCache.get(cacheKey);
	    if(cachedValue != null)
	    {
	        return cachedValue.booleanValue();
	        
	    }
	    else
	    {
	        boolean isHolder = clickedBlock.getState() instanceof InventoryHolder;
	        this.inventoryHolderCache.put(cacheKey, isHolder);
	        return isHolder;
	    }
        }

    private boolean onLeftClickWatchList(Material material)
	{
	    switch(material)
        {
            case OAK_BUTTON:
            case SPRUCE_BUTTON:
            case BIRCH_BUTTON:
            case JUNGLE_BUTTON:
            case ACACIA_BUTTON:
            case DARK_OAK_BUTTON:
            case STONE_BUTTON:
            case LEVER:
            case REPEATER:
            case CAKE:
            case DRAGON_EGG:
                return true;
            default:
                return false;
        }
    }

    static Block getTargetBlock(Player player, int maxDistance) throws IllegalStateException
	{
        Location eye = player.getEyeLocation();
        Material eyeMaterial = eye.getBlock().getType();
        boolean passThroughWater = (eyeMaterial == Material.WATER); 
        BlockIterator iterator = new BlockIterator(player.getLocation(), player.getEyeHeight(), maxDistance);
	    Block result = player.getLocation().getBlock().getRelative(BlockFace.UP);
	    while (iterator.hasNext())
	    {
	        result = iterator.next();
	        Material type = result.getType();
	        if(type != Material.AIR && 
	           (!passThroughWater || type != Material.WATER) &&
	           type != Material.GRASS &&
               type != Material.SNOW) return result;
	    }
	    
	    return result;
    }
}
