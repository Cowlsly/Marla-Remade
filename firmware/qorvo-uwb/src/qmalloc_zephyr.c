/*
 * qosal allocator backend on Zephyr's kernel heap.
 *
 * Replaces qosal/src/zephyr/qmalloc.c, which implements these on libc malloc. That
 * pulls a libc malloc provider onto the link line, and in a picolibc build two of them
 * offer one — picolibc's own nano-malloc and Zephyr's lib/libc/common — which collide.
 * Disabling Zephyr's instead leaves picolibc's wanting __heap_start/__heap_end, symbols
 * Zephyr only emits for its own allocator.
 *
 * Going to k_malloc sidesteps the whole question and puts UWB allocations in the
 * kernel heap (CONFIG_HEAP_MEM_POOL_SIZE), where they are sized and accounted with
 * everything else rather than in a second, invisible heap.
 */
#include "qmalloc.h"

#include <zephyr/kernel.h>

/*
 * Per-quota budgets consumed by qosal's wrappers in qosal/src/qmalloc.c. Index 0 is
 * MEM_QUOTA_ID_INFINITE. The optional CONFIG_MEM_QUOTA_ID* knobs are not set here, so
 * the stack runs with a single unbounded quota and the kernel heap is the real limit.
 */
uint32_t allocation_quotas[] = {
	~0U,
#ifdef CONFIG_MEM_QUOTA_ID1
	CONFIG_MEM_QUOTA_ID1,
#endif
#ifdef CONFIG_MEM_QUOTA_ID2
	CONFIG_MEM_QUOTA_ID2,
#endif
#ifdef CONFIG_MEM_QUOTA_ID3
	CONFIG_MEM_QUOTA_ID3,
#endif
#ifdef CONFIG_MEM_QUOTA_ID4
	CONFIG_MEM_QUOTA_ID4,
#endif
};

void *qmalloc_internal(size_t size)
{
	return k_malloc(size);
}

void *qrealloc_internal(void *ptr, size_t new_size)
{
	return k_realloc(ptr, new_size);
}

void qfree_internal(void *ptr)
{
	k_free(ptr);
}
