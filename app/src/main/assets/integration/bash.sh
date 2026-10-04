# puppy shell integration for Bash.
[[ $- == *i* ]] || return 0
[[ ${PUPPY_SHELL_INTEGRATION:-1} != 0 ]] || return 0
[[ ${PUPPY_SHELL_INTEGRATION_LOADED:-} == 1 ]] && return 0
PUPPY_SHELL_INTEGRATION_LOADED=1
HISTCONTROL=ignoredups:erasedups
HISTSIZE=${HISTSIZE:-10000}
shopt -s histappend

puppy_git_branch() {
    local git_dir="$PWD/.git" head
    if [[ -f $git_dir ]]; then
        IFS=' ' read -r git_dir < "$git_dir"
        git_dir=${git_dir#gitdir: }
        [[ $git_dir = /* ]] || git_dir="$PWD/$git_dir"
    fi
    [[ -r $git_dir/HEAD ]] || return 0
    IFS= read -r head < "$git_dir/HEAD" || return 0
    [[ $head == ref:refs/heads/* ]] && printf '  git:%s' "${head#ref:refs/heads/}"
}

puppy_project_module() {
    [[ -f package.json || -f .nvmrc ]] && { printf '  node'; return; }
    [[ -f pyproject.toml || -f requirements.txt || -d .venv || -d venv ]] && { printf '  python'; return; }
    [[ -f Cargo.toml ]] && { printf '  rust'; return; }
    [[ -f go.mod ]] && { printf '  go'; return; }
    [[ -f pom.xml || -f build.gradle || -f build.gradle.kts ]] && { printf '  java'; return; }
}

puppy_precmd() {
    local status=$? elapsed=$((SECONDS - ${PUPPY_COMMAND_STARTED:-SECONDS})) branch module
    printf '\033]133;D;%s\007\033]133;A\007' "$status"
    branch=$(puppy_git_branch)
    module=$(puppy_project_module)
    if (( elapsed >= 2 )); then PUPPY_DURATION="  ${elapsed}s"; else PUPPY_DURATION=; fi
    PUPPY_COMMAND_STARTED=$SECONDS
    if (( status == 0 )); then PUPPY_STATUS_COLOR='38;5;114'; else PUPPY_STATUS_COLOR='38;5;203'; fi
    PS1="\[\e[${PUPPY_STATUS_COLOR}m\]\w${branch}${module}${PUPPY_DURATION}\n❯ \[\e[0m\]\[\e]133;B\a\]"
}

PROMPT_COMMAND="${PROMPT_COMMAND:+$PROMPT_COMMAND; }puppy_precmd"
