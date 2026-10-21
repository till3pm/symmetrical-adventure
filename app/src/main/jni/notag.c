#include <malloc.h>
#include <stdio.h>

#ifndef M_BIONIC_SET_HEAP_TAGGING_LEVEL
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL (-204)
#endif

/* Schaltet Heap-Tagging (TBI/MTE) im Java-Prozess ab; die JVM entfernt Adress-Tags und stuerzt sonst ab. */
__attribute__((constructor)) static void notag_init(void) {
    int r = mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0);
    fprintf(stderr, "[notag] Heap-Tagging aus: %s\n", r ? "ok" : "FEHLGESCHLAGEN");
}
