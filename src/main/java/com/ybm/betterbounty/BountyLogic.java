package com.ybm.betterbounty;

import com.google.gson.*;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

import static com.ybm.betterbounty.BetterBounty.LOGGER;

public class BountyLogic {

    public enum ObjectiveType {
        ITEM, ITEM_TAG, CRITERIA
    }

    public static class BountyRequirement {
        public final String id;
        public final ObjectiveType type;
        public final String content;
        public final int amount;
        public final int current;
        public final Set<ResourceLocation> critItems;

        public BountyRequirement(String id, String logicId, String content, int amount, int current, @Nullable Set<ResourceLocation> critItems) {
            this.id = id;
            this.content = content;
            this.amount = amount;
            this.current = current;
            this.critItems = critItems;

            if (logicId.endsWith(":item_tag")) this.type = ObjectiveType.ITEM_TAG;
            else if (logicId.endsWith(":criteria")) this.type = ObjectiveType.CRITERIA;
            else this.type = ObjectiveType.ITEM;
        }

        public boolean isCriteriaCompleted() {
            return type == ObjectiveType.CRITERIA && current >= amount;
        }

        public boolean matches(ItemStack stack) {
            if (type == ObjectiveType.CRITERIA) return false;
            if (stack.isEmpty()) return false;

            if (type == ObjectiveType.ITEM_TAG) {
                ResourceLocation tagId = ResourceLocation.tryParse(content);
                if (tagId == null) return false;
                TagKey<Item> tag = TagKey.create(BuiltInRegistries.ITEM.key(), tagId);
                return stack.is(tag);
            } else {
                ResourceLocation itemId = ResourceLocation.tryParse(content);
                if (itemId == null) return false;
                return ForgeRegistries.ITEMS.getKey(stack.getItem()).equals(itemId);
            }
        }

        public ItemStack createDisplayStack(int count) {
            if (type == ObjectiveType.CRITERIA) {
                return new ItemStack(Items.ENCHANTED_BOOK, count);
            }
            if (type == ObjectiveType.ITEM_TAG) {
                ResourceLocation tagId = ResourceLocation.tryParse(content);
                if (tagId != null) {
                    TagKey<Item> tag = TagKey.create(BuiltInRegistries.ITEM.key(), tagId);
                    Optional<? extends net.minecraft.core.Holder<Item>> first = BuiltInRegistries.ITEM.getTag(tag)
                            .flatMap(holders -> holders.stream().findFirst());
                    if (first.isPresent()) return new ItemStack(first.get().value(), count);
                }
                return new ItemStack(Items.BARRIER, count);
            }
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(content));
            return new ItemStack(item != null && item != Items.AIR ? item : Items.BARRIER, count);
        }
    }

    public static boolean isBounty(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && id.toString().equals("bountiful:bounty");
    }

    public static boolean isBountyExpired(ItemStack bounty, ServerLevel level) {
        CompoundTag root = bounty.getOrCreateTag();
        if (!root.contains("bountiful:bounty_info", Tag.TAG_STRING)) return false;
        try {
            String jsonStr = root.getString("bountiful:bounty_info");
            JsonObject infoJson = JsonParser.parseString(jsonStr).getAsJsonObject();
            long started = infoJson.get("timeStarted").getAsLong();
            long toComplete = infoJson.get("timeToComplete").getAsLong();
            return level.getGameTime() > (started + toComplete);
        } catch (Exception e) {
            LOGGER.error("Failed to parse bounty_info", e);
            return false;
        }
    }

    public static List<BountyRequirement> getRequirements(ItemStack bounty) {
        List<BountyRequirement> reqs = new ArrayList<>();
        CompoundTag root = bounty.getOrCreateTag();
        if (!root.contains("bountiful:bounty_data", Tag.TAG_STRING)) return reqs;
        String jsonStr = root.getString("bountiful:bounty_data");
        if (jsonStr.isEmpty()) return reqs;

        try {
            JsonObject bountyData = JsonParser.parseString(jsonStr).getAsJsonObject();
            JsonArray objectives = bountyData.getAsJsonArray("objectives");
            if (objectives == null) return reqs;

            for (JsonElement element : objectives) {
                JsonObject obj = element.getAsJsonObject();
                String id = obj.get("id").getAsString();
                String logicId = obj.get("logicId").getAsString();
                String content = obj.get("content").getAsString();
                int amount = obj.get("amount").getAsInt();
                int current = obj.has("current") ? obj.get("current").getAsInt() : 0;

                Set<ResourceLocation> critItems = null;
                if (logicId.endsWith(":criteria") && obj.has("critConditions")) {
                    JsonObject crit = obj.getAsJsonObject("critConditions");
                    if (crit.has("item") && crit.get("item").isJsonObject()) {
                        JsonObject itemFilter = crit.getAsJsonObject("item");
                        if (itemFilter.has("items")) {
                            JsonArray items = itemFilter.getAsJsonArray("items");
                            critItems = new HashSet<>();
                            for (JsonElement e : items) {
                                ResourceLocation rl = ResourceLocation.tryParse(e.getAsString());
                                if (rl != null) critItems.add(rl);
                            }
                        }
                    }
                }
                reqs.add(new BountyRequirement(id, logicId, content, amount, current, critItems));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse bounty objectives", e);
        }
        return reqs;
    }

    public static void trySubmitBountyFromNetwork(ServerPlayer player, ItemStack bounty, INetwork network) {
        if (isBountyExpired(bounty, player.serverLevel())) {
            player.sendSystemMessage(Component.literal("悬赏令已过期，无法提交。"));
            return;
        }
        List<BountyRequirement> requirements = getRequirements(bounty);
        if (requirements.isEmpty()) {
            LOGGER.info("No requirements found in bounty NBT");
            return;
        }
        if (!checkAndConsumeAll(player, network, null, requirements)) return;
        finishBounty(player, bounty);
    }

    public static void trySubmitBountyFromContainer(ServerPlayer player, ItemStack bounty, IItemHandler container) {
        if (isBountyExpired(bounty, player.serverLevel())) {
            player.sendSystemMessage(Component.literal("悬赏令已过期，无法提交。"));
            return;
        }
        List<BountyRequirement> requirements = getRequirements(bounty);
        if (requirements.isEmpty()) {
            LOGGER.info("No requirements found in bounty NBT");
            return;
        }
        if (!checkAndConsumeAll(player, null, container, requirements)) return;
        finishBounty(player, bounty);
    }

    private static boolean checkAndConsumeAll(ServerPlayer player, @Nullable INetwork network,
                                              @Nullable IItemHandler externalContainer,
                                              List<BountyRequirement> requirements) {
        // 1. criteria check
        for (BountyRequirement req : requirements) {
            if (req.type == ObjectiveType.CRITERIA && !req.isCriteriaCompleted()) {
                List<ItemStack> missing = List.of(req.createDisplayStack(req.amount - req.current));
                sendMissingMessage(player, missing);
                return false;
            }
        }

        List<BountyRequirement> itemReqs = requirements.stream()
                .filter(r -> r.type != ObjectiveType.CRITERIA)
                .collect(Collectors.toList());
        if (itemReqs.isEmpty()) return true;

        // 2. 收集来源
        // 玩家主物品栏 (0-35 槽位)
        IItemHandler playerInv = new InvWrapper(player.getInventory()) {
            @Override
            public int getSlots() {
                return 36; // 只暴露主物品栏
            }
        };
        // 精妙背包内部的 handlers
        List<IItemHandler> backpackHandlers = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                IItemHandler handler = stack.getCapability(ForgeCapabilities.ITEM_HANDLER, null).resolve().orElse(null);
                if (handler != null) {
                    backpackHandlers.add(handler);
                }
            }
        }

        // 3. 模拟检查
        Map<IItemHandler, Map<Integer, Integer>> handlerExtractPlan = new HashMap<>(); // handler -> (slot -> count)
        Map<ItemStack, Integer> networkExtractPlan = new LinkedHashMap<>();
        boolean allSatisfied = true;
        List<ItemStack> missing = new ArrayList<>();

        for (BountyRequirement req : itemReqs) {
            int needed = req.amount;
            int collected = 0;

            // 从玩家物品栏收集
            collected += collectFromHandler(req, playerInv, needed - collected, handlerExtractPlan, true);
            if (collected < needed) {
                // 从所有背包内部收集
                for (IItemHandler bp : backpackHandlers) {
                    int canTake = collectFromHandler(req, bp, needed - collected, handlerExtractPlan, true);
                    collected += canTake;
                    if (collected >= needed) break;
                }
            }
            if (collected < needed && externalContainer != null) {
                collected += collectFromHandler(req, externalContainer, needed - collected, handlerExtractPlan, true);
            }
            if (collected < needed && network != null) {
                int netCollected = collectFromNetwork(req, network, needed - collected, networkExtractPlan);
                collected += netCollected;
            }

            if (collected < needed) {
                missing.add(req.createDisplayStack(needed - collected));
                allSatisfied = false;
            }
        }

        if (!allSatisfied) {
            sendMissingMessage(player, missing);
            return false;
        }

        // 4. 真实提取
        for (Map.Entry<IItemHandler, Map<Integer, Integer>> handlerEntry : handlerExtractPlan.entrySet()) {
            IItemHandler handler = handlerEntry.getKey();
            for (Map.Entry<Integer, Integer> slotEntry : handlerEntry.getValue().entrySet()) {
                handler.extractItem(slotEntry.getKey(), slotEntry.getValue(), false);
            }
        }
        for (Map.Entry<ItemStack, Integer> e : networkExtractPlan.entrySet()) {
            network.extractItem(e.getKey().copyWithCount(e.getValue()), e.getValue(), Action.PERFORM);
        }
        return true;
    }

    private static int collectFromHandler(BountyRequirement req, IItemHandler handler, int maxNeeded,
                                          Map<IItemHandler, Map<Integer, Integer>> plan, boolean simulate) {
        int collected = 0;
        for (int i = 0; i < handler.getSlots() && collected < maxNeeded; i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (req.matches(stack)) {
                int canTake = Math.min(maxNeeded - collected, stack.getCount());
                if (canTake > 0) {
                    if (simulate) {
                        plan.computeIfAbsent(handler, k -> new HashMap<>()).merge(i, canTake, Integer::sum);
                    }
                    collected += canTake;
                }
            }
        }
        return collected;
    }

    private static int collectFromNetwork(BountyRequirement req, INetwork network, int maxNeeded,
                                          Map<ItemStack, Integer> plan) {
        IStorageCache<ItemStack> cache = network.getItemStorageCache();
        List<StackListEntry<ItemStack>> entries = new ArrayList<>(cache.getList().getStacks());
        int collected = 0;
        for (StackListEntry<ItemStack> entry : entries) {
            ItemStack stack = entry.getStack();
            if (req.matches(stack)) {
                int canTake = Math.min(maxNeeded - collected, stack.getCount());
                if (canTake > 0) {
                    plan.merge(stack.copy(), canTake, Integer::sum);
                    collected += canTake;
                    if (collected >= maxNeeded) break;
                }
            }
        }
        return collected;
    }

    private static void sendMissingMessage(ServerPlayer player, List<ItemStack> missing) {
        ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new MissingItemsPacket(missing));
        StringBuilder sb = new StringBuilder();
        for (ItemStack stack : missing) {
            sb.append(stack.getDisplayName().getString()).append("x").append(stack.getCount()).append(", ");
        }
        if (sb.length() > 2) sb.setLength(sb.length() - 2);
        player.sendSystemMessage(Component.literal("物品不足，缺失：" + sb.toString() + "。已标记到 JEI。"));
    }

    private static void finishBounty(ServerPlayer player, ItemStack bounty) {
        List<ItemStack> rewards = getRewardItems(bounty);
        for (ItemStack reward : rewards) {
            ItemHandlerHelper.giveItemToPlayer(player, reward.copy());
        }
        bounty.shrink(1);
        ItemHandlerHelper.giveItemToPlayer(player, new ItemStack(ModItems.BOUNTY_REWARD.get()));
        player.sendSystemMessage(Component.literal("提交成功！获得悬赏令经验及奖励物品。"));
    }

    private static List<ItemStack> getRewardItems(ItemStack bounty) {
        List<ItemStack> rewards = new ArrayList<>();
        CompoundTag root = bounty.getOrCreateTag();
        if (!root.contains("bountiful:bounty_data", Tag.TAG_STRING)) return rewards;
        String jsonStr = root.getString("bountiful:bounty_data");
        if (jsonStr.isEmpty()) return rewards;

        try {
            JsonObject data = JsonParser.parseString(jsonStr).getAsJsonObject();
            JsonArray rewardsArr = data.getAsJsonArray("rewards");
            if (rewardsArr != null) {
                for (JsonElement e : rewardsArr) {
                    JsonObject obj = e.getAsJsonObject();
                    String content = obj.get("content").getAsString();
                    int amount = obj.get("amount").getAsInt();
                    Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(content));
                    if (item != null && item != Items.AIR) {
                        ItemStack stack = new ItemStack(item, amount);
                        if (obj.has("nbt") && !obj.get("nbt").isJsonNull()) {
                            String nbtString = obj.get("nbt").getAsString();
                            try {
                                CompoundTag tag = TagParser.parseTag(nbtString);
                                if (tag != null) stack.setTag(tag);
                            } catch (Exception ex) {
                                LOGGER.error("Failed to parse reward NBT: {}", nbtString, ex);
                            }
                        }
                        rewards.add(stack);
                    }
                }
            }
        } catch (Exception ex) {
            LOGGER.error("Failed to parse bounty rewards", ex);
        }
        return rewards;
    }
}