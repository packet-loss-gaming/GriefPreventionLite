/*
    GriefPrevention Server Plugin for Minecraft
    Copyright (C) 2012 Ryan Hamshire

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

import me.ryanhamshire.GriefPrevention.events.PreventPvPEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Explosive;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Item;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.WaterMob;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Donkey;
import org.bukkit.entity.Mule;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalExitEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ExpBottleEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingBreakEvent.RemoveCause;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

//handles events related to entities
public class EntityEventHandler implements Listener
{
	//convenience reference for the singleton datastore
	private DataStore dataStore;
	GriefPrevention instance;
	
	public EntityEventHandler(DataStore dataStore, GriefPrevention plugin)
	{
		this.dataStore = dataStore;
		instance = plugin;
	}
	
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onEntityFormBlock(EntityBlockFormEvent event)
	{
	    Entity entity = event.getEntity();
	    if(entity.getType() == EntityType.PLAYER)
        {
            Player player = (Player)event.getEntity();
            String noBuildReason = GriefPrevention.instance.allowBuild(player, event.getBlock().getLocation(), event.getNewState().getType());
            if(noBuildReason != null)
            {
                event.setCancelled(true);
            }
        }
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onLightningStrike(LightningStrikeEvent event){
		if(event.getCause() == LightningStrikeEvent.Cause.TRIDENT)
			event.getLightning().setMetadata("GP_TRIDENT", new FixedMetadataValue(GriefPrevention.instance, event.getLightning().getLocation()));
	}

	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onEntityChangeBLock(EntityChangeBlockEvent event)
	{
		if(!GriefPrevention.instance.config_endermenMoveBlocks && event.getEntityType() == EntityType.ENDERMAN)
		{
			event.setCancelled(true);
		}
		
		else if(!GriefPrevention.instance.config_silverfishBreakBlocks && event.getEntityType() == EntityType.SILVERFISH)
		{
			event.setCancelled(true);
		}
	    
		else if(!GriefPrevention.instance.config_rabbitsEatCrops && event.getEntityType() == EntityType.RABBIT)
		{
		    event.setCancelled(true);
		}
		else if(GriefPrevention.instance.config_claims_worldModes.get(event.getBlock().getWorld()) != ClaimsMode.Disabled)
        {
            if (event.getEntityType() == EntityType.WITHER)
            {
                Claim claim = this.dataStore.getClaimAt(event.getBlock().getLocation(), false, null);
                if (claim == null || !claim.areExplosivesAllowed || !GriefPrevention.instance.config_blockClaimExplosions)
                {
                    event.setCancelled(true);
                }
            }

            else if (!GriefPrevention.instance.config_claims_ravagersBreakBlocks && event.getEntityType() == EntityType.RAVAGER)
            {
                event.setCancelled(true);
            }

            //don't allow crops to be trampled, except by a player with build permission
            else if (event.getTo() == Material.DIRT && event.getBlock().getType() == Material.FARMLAND)
            {
                if (event.getEntityType() != EntityType.PLAYER)
                {
                    event.setCancelled(true);
                } else
                {
                    Player player = (Player) event.getEntity();
                    Block block = event.getBlock();
                    if (GriefPrevention.instance.allowBreak(player, block, block.getLocation()) != null)
                    {
                        event.setCancelled(true);
                    }
                }
            }
        }

            //Prevent breaking lilypads via collision with a boat. Thanks Jikoo.
            else if (event.getEntity() instanceof Vehicle && !event.getEntity().getPassengers().isEmpty()) {
                Entity driver = event.getEntity().getPassengers().get(0);
                if (driver instanceof Player) {
                    Block block = event.getBlock();
                    if (GriefPrevention.instance.allowBreak((Player) driver, block, block.getLocation()) != null) {
                        event.setCancelled(true);
                    }
                }
            }

            //sand cannon fix - when the falling block doesn't fall straight down, take additional anti-grief steps
            else if (event.getEntityType() == EntityType.FALLING_BLOCK)
            {
                FallingBlock entity = (FallingBlock)event.getEntity();
                Block block = event.getBlock();

                //if changing a block TO air, this is when the falling block formed.  note its original location
                if(event.getTo() == Material.AIR)
                {
                    entity.setMetadata("GP_FALLINGBLOCK", new FixedMetadataValue(GriefPrevention.instance, block.getLocation()));
                }
                //otherwise, the falling block is forming a block.  compare new location to original source
                else
                {
                    List<MetadataValue> values = entity.getMetadata("GP_FALLINGBLOCK");
                    //if we're not sure where this entity came from (maybe another plugin didn't follow the standard?), allow the block to form
                    //Or if entity fell through an end portal, allow it to form, as the event is erroneously fired twice in this scenario.
                    if(values.size() < 1) return;

                    Location originalLocation = (Location)(values.get(0).value());
                    Location newLocation = block.getLocation();

                    //if did not fall straight down
                    if(originalLocation.getBlockX() != newLocation.getBlockX() || originalLocation.getBlockZ() != newLocation.getBlockZ())
                    {
                        //if landing in land claim, only allow if source was also in the land claim
                        Claim claim = this.dataStore.getClaimAt(newLocation, false, null);
                        if(claim != null && !claim.contains(originalLocation, false, false))
                        {
                            //when not allowed, drop as item instead of forming a block
                            event.setCancelled(true);
                            @SuppressWarnings("deprecation")
                            ItemStack itemStack = new ItemStack(entity.getMaterial(), 1);
                            Item item = block.getWorld().dropItem(entity.getLocation(), itemStack);
                            item.setVelocity(new Vector());
                        }
                    }
                }
            }
	}

	//Used by "sand cannon" fix to ignore fallingblocks that fell through End Portals
	//This is largely due to a CB issue with the above event
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onFallingBlockEnterPortal(EntityPortalEnterEvent event)
	{
		if (event.getEntityType() != EntityType.FALLING_BLOCK)
			return;
		event.getEntity().removeMetadata("GP_FALLINGBLOCK", instance);
	}

	//Don't let people drop in TNT through end portals
	//Necessarily this shouldn't be an issue anyways since the platform is obsidian...
	@EventHandler(ignoreCancelled = true)
	void onTNTExitPortal(EntityPortalExitEvent event)
	{
		if (event.getEntityType() != EntityType.PRIMED_TNT)
			return;
		if (event.getTo().getWorld().getEnvironment() != Environment.THE_END)
			return;
		event.getEntity().remove();
	}
	
	//don't allow zombies to break down doors
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onZombieBreakDoor(EntityBreakDoorEvent event)
	{		
		if(!GriefPrevention.instance.config_zombiesBreakDoors) event.setCancelled(true);
	}
	
	//don't allow entities to trample crops
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onEntityInteract(EntityInteractEvent event)
	{
		Material material = event.getBlock().getType();
		if(material == Material.FARMLAND)
		{
			if(!GriefPrevention.instance.config_creaturesTrampleCrops)
			{
				event.setCancelled(true);
			}
			else
			{
				Entity rider = event.getEntity().getPassenger();
				if(rider != null && rider.getType() == EntityType.PLAYER)
				{
					event.setCancelled(true);
				}
			}
		}
	}
	
	//when an entity explodes...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onEntityExplode(EntityExplodeEvent explodeEvent)
	{		
		this.handleExplosion(explodeEvent.getLocation(), explodeEvent.getEntity(), explodeEvent.blockList());
	}
	
	//when a block explodes...
    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onBlockExplode(BlockExplodeEvent explodeEvent)
    {       
        this.handleExplosion(explodeEvent.getBlock().getLocation(), null, explodeEvent.blockList());
    }
    
    @SuppressWarnings("deprecation")
    void handleExplosion(Location location, Entity entity, List<Block> blocks)
    {
        //only applies to claims-enabled worlds
        World world = location.getWorld();
        
        if(!GriefPrevention.instance.claimsEnabledForWorld(world)) return;
        
        //FEATURE: explosions don't destroy surface blocks by default
        boolean isCreeper = (entity != null && entity.getType() == EntityType.CREEPER);
        
        boolean applySurfaceRules = world.getEnvironment() == Environment.NORMAL && ((isCreeper && GriefPrevention.instance.config_blockSurfaceCreeperExplosions) || (!isCreeper && GriefPrevention.instance.config_blockSurfaceOtherExplosions));

        //make a list of blocks which were allowed to explode
        List<Block> explodedBlocks = new ArrayList<Block>();
        Claim cachedClaim = null;
        for(int i = 0; i < blocks.size(); i++)
        {
            Block block = blocks.get(i);
            
            //always ignore air blocks
            if(block.getType() == Material.AIR) continue;
            
            //is it in a land claim?
            Claim claim = this.dataStore.getClaimAt(block.getLocation(), false, cachedClaim);
            if(claim != null)
            {
                cachedClaim = claim;
            }
                    
            //if yes, apply claim exemptions if they should apply
            if(claim != null && (claim.areExplosivesAllowed || !GriefPrevention.instance.config_blockClaimExplosions))
            {
                explodedBlocks.add(block);
                continue;
            }
            
            //if no, then also consider surface rules
            if(claim == null)
            {
                if(!applySurfaceRules || block.getLocation().getBlockY() < GriefPrevention.instance.getSeaLevel(world) - 7)
                {
                    explodedBlocks.add(block);
                }
            }
        }
        
        //clear original damage list and replace with allowed damage list
        blocks.clear();
        blocks.addAll(explodedBlocks);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntitySpawn(CreatureSpawnEvent event)
    {
        if (!GriefPrevention.instance.config_claims_preventClaimMonsterSpawns) return;
        if (event.getSpawnReason() != SpawnReason.NATURAL) return;

        Entity entity = event.getEntity();
        if (!(entity instanceof Monster)) return;

        Claim claim = this.dataStore.getClaimAt(event.getLocation(), false, null);
        if(claim == null)
        {
            return;
        }

        event.setCancelled(true);
    }

	//when an entity picks up an item
	@EventHandler(priority = EventPriority.LOWEST)
	public void onEntityPickup(EntityChangeBlockEvent event)
	{
		//FEATURE: endermen don't steal claimed blocks
		
		//if its an enderman
		if(event.getEntity().getType() == EntityType.ENDERMAN)
		{
			//and the block is claimed
			if(this.dataStore.getClaimAt(event.getBlock().getLocation(), false, null) != null)
			{
				//he doesn't get to steal it
				event.setCancelled(true);
			}
		}
	}
	
	//when a painting is broken
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onHangingBreak(HangingBreakEvent event)
    {
	    //don't track in worlds where claims are not enabled
        if(!GriefPrevention.instance.claimsEnabledForWorld(event.getEntity().getWorld())) return;

        //Ignore cases where itemframes should break due to no supporting blocks
		if(event.getCause() == RemoveCause.PHYSICS) return;
	    
	    //FEATURE: claimed paintings are protected from breakage
		
		//explosions don't destroy hangings
	    if(event.getCause() == RemoveCause.EXPLOSION)
	    {
	        event.setCancelled(true);
	        return;
	    }
	    
	    //only allow players to break paintings, not anything else (like water and explosions)
		if(!(event instanceof HangingBreakByEntityEvent))
    	{
        	event.setCancelled(true);
        	return;
    	}
        
        HangingBreakByEntityEvent entityEvent = (HangingBreakByEntityEvent)event;
        
        //who is removing it?
		Entity remover = entityEvent.getRemover();
        
		//again, making sure the breaker is a player
		if(remover.getType() != EntityType.PLAYER)
        {
        	event.setCancelled(true);
        	return;
        }
		
		//if the player doesn't have build permission, don't allow the breakage
		Player playerRemover = (Player)entityEvent.getRemover();
        String noBuildReason = GriefPrevention.instance.allowBuild(playerRemover, event.getEntity().getLocation(), Material.AIR);
        if(noBuildReason != null)
        {
        	event.setCancelled(true);
        	GriefPrevention.sendMessage(playerRemover, TextMode.Err, noBuildReason);
        }
    }
	
	//when a painting is placed...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onPaintingPlace(HangingPlaceEvent event)
	{
	    //don't track in worlds where claims are not enabled
        if(!GriefPrevention.instance.claimsEnabledForWorld(event.getBlock().getWorld())) return;
	    
	    //FEATURE: similar to above, placing a painting requires build permission in the claim
	
		//if the player doesn't have permission, don't allow the placement
		String noBuildReason = GriefPrevention.instance.allowBuild(event.getPlayer(), event.getEntity().getLocation(), Material.PAINTING);
        if(noBuildReason != null)
        {
        	event.setCancelled(true);
        	GriefPrevention.sendMessage(event.getPlayer(), TextMode.Err, noBuildReason);
			return;
        }
	}
	
	private boolean isMonster(Entity entity)
    {
        if(entity instanceof Monster) return true;
        
        EntityType type = entity.getType();
        if(type == EntityType.GHAST || type == EntityType.MAGMA_CUBE || type == EntityType.SHULKER || type == EntityType.POLAR_BEAR) return true;
        
        if(type == EntityType.RABBIT)
        {
            Rabbit rabbit = (Rabbit)entity;
            if(rabbit.getRabbitType() == Rabbit.Type.THE_KILLER_BUNNY) return true;
        }
        
        return false;
    }
	
	//when an entity is damaged
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onEntityDamage (EntityDamageEvent event)
	{
		this.handleEntityDamageEvent(event, true);
	}
	
	//when an entity is set on fire
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onEntityCombustByEntity (EntityCombustByEntityEvent event)
	{
	    //handle it just like we would an entity damge by entity event, except don't send player messages to avoid double messages
	    //in cases like attacking with a flame sword or flame arrow, which would ALSO trigger the direct damage event handler
	    @SuppressWarnings("deprecation")
        EntityDamageByEntityEvent eventWrapper = new EntityDamageByEntityEvent(event.getCombuster(), event.getEntity(), DamageCause.FIRE_TICK, event.getDuration());
	    this.handleEntityDamageEvent(eventWrapper, false);
	    event.setCancelled(eventWrapper.isCancelled());
	}
	
	private void handleEntityDamageEvent(EntityDamageEvent event, boolean sendErrorMessagesToPlayers)
	{
	    //monsters are never protected
        if(isMonster(event.getEntity())) return;
        
        //horse protections can be disabled
        if(event.getEntity() instanceof Horse && !GriefPrevention.instance.config_claims_protectHorses) return;
        if(event.getEntity() instanceof Donkey && !GriefPrevention.instance.config_claims_protectDonkeys) return;
        if(event.getEntity() instanceof Mule && !GriefPrevention.instance.config_claims_protectDonkeys) return;
        if(event.getEntity() instanceof Llama && !GriefPrevention.instance.config_claims_protectLlamas) return;

        //the rest is only interested in entities damaging entities (ignoring environmental damage)
        if(!(event instanceof EntityDamageByEntityEvent)) return;
        
        EntityDamageByEntityEvent subEvent = (EntityDamageByEntityEvent) event;

        if(subEvent.getDamager() instanceof LightningStrike && subEvent.getDamager().hasMetadata("GP_TRIDENT"))
        {
			event.setCancelled(true);
			return;
		}
        //determine which player is attacking, if any
        Player attacker = null;
        Projectile arrow = null;
        Firework firework = null;
        Entity damageSource = subEvent.getDamager();
        
        if(damageSource != null)
        {
            if(damageSource.getType() == EntityType.PLAYER)
            {
                attacker = (Player)damageSource;
            }
            else if(damageSource instanceof Projectile)
            {
                arrow = (Projectile)damageSource;
                if(arrow.getShooter() instanceof Player)
                {
                    attacker = (Player)arrow.getShooter();
                }
            }else if(subEvent.getDamager() instanceof Firework) {
	        	damageSource = subEvent.getDamager();
    	        if(damageSource.hasMetadata("GP_FIREWORK")) {
    		        List<MetadataValue> data = damageSource.getMetadata("GP_FIREWORK");
    		        if(data != null && data.size() > 0)
    		        {
    		        	firework = (Firework)damageSource;
    		        	attacker = (Player) data.get(0).value();
    		        }
    	        }
            }
        }
        
        //if the attacker is a firework from a crossbow by a player and defender is a player (nonpvp)
        if(firework != null && event.getEntityType() == EntityType.PLAYER)
        {
        	Player defender = (Player)(event.getEntity());
        	if(attacker != defender) {
        		event.setCancelled(true);
        		return;
        	}
        }
        
        if(event instanceof EntityDamageByEntityEvent)
        {
            //don't track in worlds where claims are not enabled
            if(!GriefPrevention.instance.claimsEnabledForWorld(event.getEntity().getWorld())) return;

            //if the damaged entity is a claimed item frame or armor stand, the damager needs to be a player with build trust in the claim
            if(subEvent.getEntityType() == EntityType.ITEM_FRAME
               || subEvent.getEntityType() == EntityType.ARMOR_STAND
               || subEvent.getEntityType() == EntityType.VILLAGER
               || subEvent.getEntityType() == EntityType.ENDER_CRYSTAL)
            {
                //allow for disabling villager protections in the config
                if(subEvent.getEntityType() == EntityType.VILLAGER && !GriefPrevention.instance.config_claims_protectCreatures) return;
                
                //don't protect polar bears, they may be aggressive
                if(subEvent.getEntityType() == EntityType.POLAR_BEAR) return;
                
                //decide whether it's claimed
                Claim cachedClaim = null;
                PlayerData playerData = null;
                if(attacker != null)
                {
                    playerData = this.dataStore.getPlayerData(attacker.getUniqueId());
                    cachedClaim = playerData.lastClaim;
                }
                
                Claim claim = this.dataStore.getClaimAt(event.getEntity().getLocation(), false, cachedClaim);
                
                //if it's claimed
                if(claim != null)
                {
                    //if attacker isn't a player, cancel
                    if(attacker == null)
                    {
                        //exception case
                        if(event.getEntityType() == EntityType.VILLAGER && damageSource != null && damageSource.getType() == EntityType.ZOMBIE)
                        {
                            return;
                        }
                        
                        event.setCancelled(true);
                        return;
                    }
                    
                    //otherwise player must have container trust in the claim
                    String failureReason = claim.allowBuild(attacker, Material.AIR);
                    if(failureReason != null)
                    {
                        event.setCancelled(true);
                        if(sendErrorMessagesToPlayers) GriefPrevention.sendMessage(attacker, TextMode.Err, failureReason);
                        return;
                    }
                }
            }
            
            //if the entity is an non-monster creature (remember monsters disqualified above), or a vehicle
            if (((subEvent.getEntity() instanceof Creature || subEvent.getEntity() instanceof WaterMob) && GriefPrevention.instance.config_claims_protectCreatures))
            {
                Claim cachedClaim = null;
                PlayerData playerData = null;
                
                //if not a player or an explosive, allow
				//RoboMWM: Or a lingering potion, or a witch
                if(attacker == null
                        && damageSource != null
                        && damageSource.getType() != EntityType.CREEPER
                        && damageSource.getType() != EntityType.WITHER
                        && damageSource.getType() != EntityType.ENDER_CRYSTAL
                        && damageSource.getType() != EntityType.AREA_EFFECT_CLOUD
						&& damageSource.getType() != EntityType.WITCH
                        && !(damageSource instanceof Projectile)
                        && !(damageSource instanceof Explosive)
                        && !(damageSource instanceof ExplosiveMinecart))
                {
                    return;
                }
                
                if(attacker != null)
                {
                    playerData = this.dataStore.getPlayerData(attacker.getUniqueId());
                    cachedClaim = playerData.lastClaim;
                }
                
                Claim claim = this.dataStore.getClaimAt(event.getEntity().getLocation(), false, cachedClaim);
                
                //if it's claimed
                if(claim != null)
                {
                    //if damaged by anything other than a player (exception villagers injured by zombies in admin claims), cancel the event
                    //why exception?  so admins can set up a village which can't be CHANGED by players, but must be "protected" by players.
					//TODO: Discuss if this should only apply to admin claims...?
                    if(attacker == null)
                    {
                        //exception case
                        if(event.getEntityType() == EntityType.VILLAGER && damageSource != null && (damageSource.getType() == EntityType.ZOMBIE || damageSource.getType() == EntityType.VINDICATOR || damageSource.getType() == EntityType.EVOKER || damageSource.getType() == EntityType.EVOKER_FANGS || damageSource.getType() == EntityType.VEX))
                        {
                            return;
                        }
                        
                        //all other cases
                        else
                        {
                            event.setCancelled(true);
                            if(damageSource instanceof Projectile)
                            {
                                damageSource.remove();
                            }
                        }                       
                    }
                    
                    //otherwise the player damaging the entity must have permission, unless it's a dog in a pvp world
                    else if(!(event.getEntity().getWorld().getPVP() && event.getEntity().getType() == EntityType.WOLF))
                    {
                        String noContainersReason = claim.allowContainers(attacker);
                        if(noContainersReason != null)
                        {
                            event.setCancelled(true);
                            
                            //kill the arrow to avoid infinite bounce between crowded together animals //RoboMWM: except for tridents
                            if(arrow != null && arrow.getType() != EntityType.TRIDENT) arrow.remove();
                            if(damageSource != null && damageSource.getType() == EntityType.FIREWORK && event.getEntity().getType() != EntityType.PLAYER) return;
                            
                            if(sendErrorMessagesToPlayers)
                            {
                                String message = GriefPrevention.instance.dataStore.getMessage(Messages.NoDamageClaimedEntity, claim.getOwnerName());
                                if(attacker.hasPermission("griefprevention.ignoreclaims"))
                                    message += "  " + GriefPrevention.instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
                                GriefPrevention.sendMessage(attacker, TextMode.Err, message);
                            }
                            event.setCancelled(true);
                        }
                        
                        //cache claim for later
                        if(playerData != null)
                        {
                            playerData.lastClaim = claim;
                        }                       
                    }
                }
            }
        }
	}
	
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onCrossbowFireWork(EntityShootBowEvent shootEvent) {
		if(shootEvent.getEntity() instanceof Player && shootEvent.getProjectile() instanceof Firework) {
			shootEvent.getProjectile().setMetadata("GP_FIREWORK", new FixedMetadataValue(GriefPrevention.instance, shootEvent.getEntity()));
		}
	}
	
	//when a vehicle is damaged
	@EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
	public void onVehicleDamage (VehicleDamageEvent event)
	{
		//all of this is anti theft code
		if(!GriefPrevention.instance.config_claims_preventTheft) return;	
		
		//input validation
		if(event.getVehicle() == null) return;
		
		//don't track in worlds where claims are not enabled
        if(!GriefPrevention.instance.claimsEnabledForWorld(event.getVehicle().getWorld())) return;
		
		//determine which player is attacking, if any
		Player attacker = null;
		Entity damageSource = event.getAttacker();
		EntityType damageSourceType = null;

		//if damage source is null or a creeper, don't allow the damage when the vehicle is in a land claim
		if(damageSource != null)
		{
		    damageSourceType = damageSource.getType();

		    if(damageSource.getType() == EntityType.PLAYER)
    		{
    			attacker = (Player)damageSource;
    		}
    		else if(damageSource instanceof Projectile)
    		{
    		    Projectile arrow = (Projectile)damageSource;
    		    if(arrow.getShooter() instanceof Player)
    			{
    				attacker = (Player)arrow.getShooter();
    			}
    		}
            else if(damageSource instanceof Firework) {
    	        if(damageSource.hasMetadata("GP_FIREWORK")) {
    		        List<MetadataValue> data = damageSource.getMetadata("GP_FIREWORK");
    		        if(data != null && data.size() > 0)
    		        {
    		        	attacker = (Player) data.get(0).value();
    		        }
    	        }
            }
		}
		
		//if not a player and not an explosion, always allow
        if(attacker == null && damageSourceType != EntityType.CREEPER && damageSourceType != EntityType.WITHER && damageSourceType != EntityType.PRIMED_TNT)
        {
            return;
        }
		
		//NOTE: vehicles can be pushed around.
		//so unless precautions are taken by the owner, a resourceful thief might find ways to steal anyway
		Claim cachedClaim = null;
		PlayerData playerData = null;
		
		if(attacker != null)
		{
			playerData = this.dataStore.getPlayerData(attacker.getUniqueId());
			cachedClaim = playerData.lastClaim;
		}
		
		Claim claim = this.dataStore.getClaimAt(event.getVehicle().getLocation(), false, cachedClaim);
		
		//if it's claimed
		if(claim != null)
		{
			//if damaged by anything other than a player, cancel the event
			if(attacker == null)
			{
				event.setCancelled(true);
			}
			
			//otherwise the player damaging the entity must have permission
			else
			{		
				String noContainersReason = claim.allowContainers(attacker);
				if(noContainersReason != null)
				{
					event.setCancelled(true);
					String message = GriefPrevention.instance.dataStore.getMessage(Messages.NoDamageClaimedEntity, claim.getOwnerName());
                    if(attacker.hasPermission("griefprevention.ignoreclaims"))
                        message += "  " + GriefPrevention.instance.dataStore.getMessage(Messages.IgnoreClaimsAdvertisement);
                    GriefPrevention.sendMessage(attacker, TextMode.Err, message);
                    event.setCancelled(true);
				}
				
				//cache claim for later
				if(playerData != null)
				{
					playerData.lastClaim = claim;
				}						
			}
		}
	}
	
	//when a splash potion effects one or more entities...
	@EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPotionSplash (PotionSplashEvent event)
	{
	    ThrownPotion potion = event.getPotion();
	    
	    //ignore potions not thrown by players
	    ProjectileSource projectileSource = potion.getShooter();
        if(projectileSource == null) return;
        Player thrower = null;
		if ((projectileSource instanceof Player))
				thrower = (Player)projectileSource;

		//Otherwise, ignore potions not thrown by players
		if (thrower == null) return;

		Collection<PotionEffect> effects = potion.getEffects();
	    for(PotionEffect effect : effects)
	    {
	        PotionEffectType effectType = effect.getType();

	        //restrict some potions on claimed animals (griefers could use this to kill or steal animals over fences) //RoboMWM: include villagers
	        if(effectType.getName().equals("JUMP") || effectType.getName().equals("POISON"))
	        {
				Claim cachedClaim = null;
				for(LivingEntity effected : event.getAffectedEntities())
	            {
	                if(effected.getType() == EntityType.VILLAGER || effected instanceof Animals)
	                {
	                    Claim claim = this.dataStore.getClaimAt(effected.getLocation(), false, cachedClaim);
	                      if(claim != null)
	                      {
	                          cachedClaim = claim;
	                          if(claim.allowContainers(thrower) != null)
	                          {
								  event.setIntensity(effected, 0);
	                              instance.sendMessage(thrower, TextMode.Err, Messages.NoDamageClaimedEntity, claim.getOwnerName());
	                              return;
	                          }
	                      }
	                }
	            }
	        }

	    }
	}
	
	public static final HashSet<PotionEffectType> positiveEffects = new HashSet<PotionEffectType>(Arrays.asList
	(
	    PotionEffectType.ABSORPTION,
	    PotionEffectType.DAMAGE_RESISTANCE,
	    PotionEffectType.FAST_DIGGING,
	    PotionEffectType.FIRE_RESISTANCE,
	    PotionEffectType.HEAL,
	    PotionEffectType.HEALTH_BOOST,
	    PotionEffectType.INCREASE_DAMAGE,
	    PotionEffectType.INVISIBILITY,
	    PotionEffectType.JUMP,
	    PotionEffectType.NIGHT_VISION,
	    PotionEffectType.REGENERATION,
	    PotionEffectType.SATURATION,
	    PotionEffectType.SPEED,
	    PotionEffectType.WATER_BREATHING
	));
}
