/*
 * Zephyr thread backend for Qorvo's qosal.
 *
 * Replaces qosal/src/zephyr/qthread.c, which hands the caller's buffer and size straight to
 * k_thread_create(). The MAC threads created through it faulted the instant they were
 * scheduled with "Illegal load of EXC_RETURN into PC" and an all-zero exception frame — the
 * signature of a thread whose stack is unusable, either misaligned or too small for
 * Zephyr's frame plus MPU guard.
 *
 * Rather than trust the supplied buffer, this allocates the stack with
 * k_thread_stack_alloc(), which is the only allocator that guarantees what Zephyr's thread
 * setup assumes about alignment and size on an MPU target. The requested size is honoured
 * as a floor, with a minimum applied because a stack sized for FreeRTOS (whose sizes are in
 * words, not bytes) would be a quarter of what it needs to be here.
 *
 * The requested parameters are logged so a stack that is being under-provisioned by the
 * caller is visible rather than presenting as a corrupt frame.
 */
#include "qthread.h"

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/util.h>

#include <string.h>

LOG_MODULE_REGISTER(qorvo_qthread, CONFIG_QORVO_UWB_LOG_LEVEL);

/* The MAC creates a couple of threads; a small fixed pool avoids a second allocator. */
#define MAX_THREADS 4

/*
 * Floor for a MAC thread. Frame assembly, crypto and region callbacks all nest on these,
 * and anything the caller asks for below this is treated as a FreeRTOS-style word count
 * rather than a byte count.
 */
#define MIN_STACK_SIZE 2048

struct qthread {
	struct k_thread thread;
	k_thread_stack_t *stack;
	bool used;
};

static struct qthread threads[MAX_THREADS];

static void zephyr_thread_entry(void *p1, void *p2, void *p3)
{
	qthread_func func = p1;

	ARG_UNUSED(p3);
	func(p2);
}

struct qthread *qthread_create(qthread_func thread, void *arg, const char *name, void *stack,
			       uint32_t stack_size, enum qthread_priority prio)
{
	struct qthread *th = NULL;
	size_t size;

	if (thread == NULL || name == NULL) {
		return NULL;
	}
	if (prio >= QTHREAD_PRIORITY_MAX) {
		return NULL;
	}

	size = MAX(stack_size, MIN_STACK_SIZE);
	LOG_INF("qthread_create('%s'): caller stack %p size %u prio %d -> allocating %u bytes",
		name, stack, stack_size, (int)prio, (unsigned int)size);

	for (int i = 0; i < MAX_THREADS; i++) {
		if (!threads[i].used) {
			th = &threads[i];
			break;
		}
	}
	if (th == NULL) {
		LOG_ERR("out of qthread slots");
		return NULL;
	}

	/*
	 * The caller's buffer is deliberately ignored. k_thread_stack_alloc is the only way
	 * to get memory Zephyr will accept as a thread stack on an MPU target; a plain heap
	 * buffer is not interchangeable with one, however well aligned.
	 */
	th->stack = k_thread_stack_alloc(size, 0);
	if (th->stack == NULL) {
		LOG_ERR("k_thread_stack_alloc(%u) failed for '%s'", (unsigned int)size, name);
		return NULL;
	}

	th->used = true;
	k_thread_create(&th->thread, th->stack, size, zephyr_thread_entry, thread, arg, NULL,
			K_PRIO_PREEMPT((int)prio), 0, K_NO_WAIT);
	k_thread_name_set(&th->thread, name);
	return th;
}

static void release(struct qthread *thread)
{
	if (thread->stack != NULL) {
		(void)k_thread_stack_free(thread->stack);
		thread->stack = NULL;
	}
	thread->used = false;
}

enum qerr qthread_join(struct qthread *thread)
{
	if (thread == NULL || !thread->used) {
		return QERR_EINVAL;
	}
	if (k_thread_join(&thread->thread, K_FOREVER)) {
		return QERR_EIO;
	}
	release(thread);
	return QERR_SUCCESS;
}

enum qerr qthread_delete(struct qthread *thread)
{
	if (thread == NULL || !thread->used) {
		return QERR_EINVAL;
	}
	k_thread_abort(&thread->thread);
	release(thread);
	return QERR_SUCCESS;
}
