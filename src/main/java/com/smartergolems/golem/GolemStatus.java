package com.smartergolems.golem;

import com.smartergolems.SmarterGolems;
import com.smartergolems.task.GolemTaskType;
import com.smartergolems.work.Activity;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.animal.golem.CopperGolem;

/**
 * A working golem's task and current activity, synced to every client so it can be shown
 * above the golem's head. Not saved: the worker sets it again on its first tick after loading.
 */
public record GolemStatus(GolemTaskType task, Activity activity) {
	public static final StreamCodec<RegistryFriendlyByteBuf, GolemStatus> STREAM_CODEC = StreamCodec.composite(
		GolemTaskType.STREAM_CODEC, GolemStatus::task,
		Activity.STREAM_CODEC, GolemStatus::activity,
		GolemStatus::new
	);

	public static final AttachmentType<GolemStatus> ATTACHMENT = AttachmentRegistry.create(
		SmarterGolems.id("status"),
		builder -> builder.syncWith(STREAM_CODEC, AttachmentSyncPredicate.all())
	);

	/** Forces class loading so the attachment type registers during mod init. */
	public static void init() {}

	/** Updates the synced status, only sending a change to clients when it actually differs. */
	public static void set(CopperGolem golem, GolemTaskType task, Activity activity) {
		GolemStatus status = new GolemStatus(task, activity);
		if (!status.equals(golem.getAttached(ATTACHMENT))) {
			golem.setAttached(ATTACHMENT, status);
		}
	}

	public static void clear(CopperGolem golem) {
		golem.removeAttached(ATTACHMENT);
	}
}
