/*
 * Aegis atomic-output compiler wrapper (GN `cc_wrapper`).
 *
 * Why: the free CI banks the ninja build tree between runs (the 6 h job cap
 * kills the build mid-compile). When ninja/clang are killed, a half-written
 * .o could remain in the tree; ninja would then consider it up to date on
 * resume and link corrupt objects. This wrapper redirects -o to a temporary
 * file and atomically renames it into place ONLY on compiler success, so
 * every object in the tree is guaranteed complete.
 *
 * Usage (GN prepends it before the compiler):
 *   aegis_atomic_cc clang++ ... -o obj/foo.o ...
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>

int main(int argc, char **argv) {
    if (argc < 2) return 2;                 /* no compiler given */

    int n = argc - 1;                       /* child argv: compiler + flags */
    char **child = malloc((n + 1) * sizeof(char *));
    if (!child) return 126;
    for (int i = 0; i < n; i++) child[i] = argv[i + 1];
    child[n] = NULL;

    /* locate the LAST -o <path> pair (compile commands have exactly one) */
    int oi = -1;
    for (int i = 0; i < n - 1; i++)
        if (strcmp(child[i], "-o") == 0) oi = i + 1;
    if (oi < 0 || child[oi] == NULL || child[oi][0] == '\0') {
        execvp(child[0], child);            /* non-compiling invocation */
        perror("aegis_atomic_cc: execvp");
        return 127;
    }

    char *out = child[oi];
    size_t len = strlen(out) + 48;
    char *tmp = malloc(len);
    if (!tmp) return 126;
    snprintf(tmp, len, "%s.aegis-tmp-%d", out, (int)getpid());
    child[oi] = tmp;

    pid_t pid = fork();
    if (pid < 0) { perror("aegis_atomic_cc: fork"); return 126; }
    if (pid == 0) {
        execvp(child[0], child);
        perror("aegis_atomic_cc: execvp");
        _exit(127);
    }
    int st = 0;
    if (waitpid(pid, &st, 0) < 0) return 126;
    int code = WIFEXITED(st) ? WEXITSTATUS(st) : 1;

    if (code == 0) {
        if (rename(tmp, out) != 0) {        /* same dir -> atomic */
            perror("aegis_atomic_cc: rename");
            code = 1;
        }
    } else {
        unlink(tmp);                        /* never leave partial output */
    }
    return code;
}
