package com.smartergolems.golem;

import com.smartergolems.task.GolemTaskType;
import com.smartergolems.work.TaskWorker;
import net.minecraft.server.level.ServerLevel;

/** Added to CopperGolem by mixin: gives each golem its own (non-saved) work state. */
public interface WorkingGolem {
	/** The worker for this task type, created fresh if the golem was doing something else. */
	TaskWorker smartergolems$workerFor(GolemTaskType type);

	/** Stops the current worker, if any (closes chests, stops walking). */
	void smartergolems$resetWorker(ServerLevel level);
}
