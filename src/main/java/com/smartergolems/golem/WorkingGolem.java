package com.smartergolems.golem;

import com.smartergolems.farming.FarmingWorker;

/** Added to CopperGolem by mixin: gives each golem its own (non-saved) work state. */
public interface WorkingGolem {
	FarmingWorker smartergolems$getFarmingWorker();
}
