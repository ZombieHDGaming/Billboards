package me.zhdg.billboards.mixin;

import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.RealTimeMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.locks.StampedLock;

@Mixin(value = EditorState.class, remap = false)
public interface EditorStateAccessor {

	/** Lets us evaluate tracks with the same real-time (tickrate-aware) mapping Flashback uses. */
	@Accessor("realTimeMapping")
	RealTimeMapping billboards$getRealTimeMapping();

	/** Used with tryReadLock/tryWriteLock so we never block on a lock the timeline already holds. */
	@Accessor("sceneLock")
	StampedLock billboards$getSceneLock();
}
