# puppy shell integration. This script is sourced by interactive POSIX shells.
case "$-" in *i*) ;; *) return 0 ;; esac
[ "${PUPPY_SHELL_INTEGRATION:-1}" = 0 ] && return 0
[ "${PUPPY_SHELL_INTEGRATION_LOADED:-}" = 1 ] && return 0
PUPPY_SHELL_INTEGRATION_LOADED=1

# BusyBox ash does not expose a pre-prompt hook. Keep this fallback static and
# let its prompt expander update only the working directory; never run a scan
# or command substitution for each prompt redraw.
puppy_esc=$(printf '\033')
puppy_bel=$(printf '\007')
PS1="${puppy_esc}]133;A${puppy_bel}${puppy_esc}[38;5;114m\\w${puppy_esc}[0m\n❯ ${puppy_esc}]133;B${puppy_bel}"
