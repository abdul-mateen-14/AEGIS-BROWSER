/*
 * atomic_cc.c - Aegis CI compile wrapper (GN cc_wrapper).
 *
 * Makes every compiler/tool output atomic: the real command runs with its
 * -o target redirected to a private temp file next to the real output, and
 * only renames the temp onto the real name if the command exits 0. A build
 * killed at the 6 h cap (or stopped by ninja SIGINT during graceful banking)
 * therefore never leaves a half-written .o / .pcm behind for the next run's
 * ninja to trust.
 *
 * Semantics:
 *   - no -o found                -> exec the command unchanged
 *   - -o under /dev or "-"       -> exec unchanged (rename would break them)
 *   - otherwise                  -> -o <out> becomes -o <out>.aegis-tmp-<pid>
 *
 * Exit code, stdout and stderr pass through unchanged. Because the temp
 * lives in the same directory as the output, rename(2) is atomic on the
 * same filesystem.
 *
 * Build:  cc -O2 -o aegis_atomic_cc atomic_cc.c
 */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>

int main(int argc, char **argv) {
    if (argc < 2)
        return 2;

    /* Find the last -o and the argument that follows it. */
    int last_o = -1;
    for (int i = 1; i < argc - 1; i++)
        if (strcmp(argv[i], "-o") == 0)
            last_o = i + 1;

    if (last_o < 0) {
        execvp(argv[1], &argv[1]);
        fprintf(stderr, "aegis_atomic_cc: execvp %s: %s\n", argv[1], strerror(errno));
        return 127;
    }

    const char *out = argv[last_o];
    if (out[0] == '-' || strncmp(out, "/dev/", 5) == 0) {
        /* "-o -" or "-o /dev/null" style probes: pass through untouched. */
        execvp(argv[1], &argv[1]);
        fprintf(stderr, "aegis_atomic_cc: execvp %s: %s\n", argv[1], strerror(errno));
        return 127;
    }

    char tmp[4096];
    int n = snprintf(tmp, sizeof(tmp), "%s.aegis-tmp-%ld", out, (long)getpid());
    if (n <= 0 || (size_t)n >= sizeof(tmp)) {
        fprintf(stderr, "aegis_atomic_cc: output path too long: %s\n", out);
        return 2;
    }

    argv[last_o] = tmp; /* child compiles into the temp file */

    pid_t pid = fork();
    if (pid < 0) {
        perror("aegis_atomic_cc: fork");
        return 2;
    }
    if (pid == 0) {
        execvp(argv[1], &argv[1]);
        fprintf(stderr, "aegis_atomic_cc: execvp %s: %s\n", argv[1], strerror(errno));
        _exit(127);
    }

    int st = 0;
    if (waitpid(pid, &st, 0) < 0) {
        unlink(tmp);
        perror("aegis_atomic_cc: waitpid");
        return 2;
    }

    if (WIFEXITED(st) && WEXITSTATUS(st) == 0) {
        if (rename(tmp, out) != 0) {
            unlink(tmp);
            fprintf(stderr, "aegis_atomic_cc: rename %s -> %s: %s\n",
                    tmp, out, strerror(errno));
            return 2;
        }
        return 0; /* output appeared atomically, complete */
    }

    unlink(tmp); /* killed or failed: leave NO output behind */
    return WIFEXITED(st) ? WEXITSTATUS(st) : 128 + WTERMSIG(st);
}
