#ifndef PODOR_ENGINE_H
#define PODOR_ENGINE_H
#include <stdint.h>
#include <stddef.h>
typedef struct { uint8_t *data; size_t length; uint32_t error; } PodorBuffer;
uint64_t podor_create(uint32_t width, uint32_t height);
void podor_destroy(uint64_t handle);
PodorBuffer podor_call(uint64_t handle, uint32_t operation, const uint8_t *data, size_t length);
void podor_free(PodorBuffer buffer);
#endif
