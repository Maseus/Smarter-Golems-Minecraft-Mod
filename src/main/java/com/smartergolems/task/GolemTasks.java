package com.smartergolems.task;

import com.smartergolems.SmarterGolems;
import java.util.Optional;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.world.entity.animal.golem.CopperGolem;

/**
 * Stores each golem's task on the entity itself via Fabric's Data Attachment API,
 * so it is saved with the golem and survives world reloads.
 * A golem with no attachment has no task and behaves like vanilla.
 */
public final class GolemTasks {
	public static final AttachmentType<GolemTask> TASK = AttachmentRegistry.create(
		SmarterGolems.id("task"),
		builder -> builder.persistent(GolemTask.CODEC)
	);

	private GolemTasks() {}

	/** Forces class loading so the attachment type registers during mod init. */
	public static void init() {}

	public static Optional<GolemTask> get(CopperGolem golem) {
		return Optional.ofNullable(golem.getAttached(TASK));
	}

	public static void set(CopperGolem golem, GolemTask task) {
		golem.setAttached(TASK, task);
	}

	public static void clear(CopperGolem golem) {
		golem.removeAttached(TASK);
	}
}
