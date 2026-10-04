# puppy shell integration for Zsh.
[[ -o interactive ]] || return 0
[[ ${PUPPY_SHELL_INTEGRATION:-1} != 0 ]] || return 0
[[ ${PUPPY_SHELL_INTEGRATION_LOADED:-} == 1 ]] && return 0
PUPPY_SHELL_INTEGRATION_LOADED=1
HISTSIZE=${HISTSIZE:-10000}
SAVEHIST=${SAVEHIST:-10000}
setopt APPEND_HISTORY HIST_IGNORE_DUPS HIST_SAVE_NO_DUPS
autoload -Uz add-zsh-hook

puppy_git_branch() {
    local git_dir="$PWD/.git" head
    if [[ -f $git_dir ]]; then
        IFS=' ' read -r git_dir < "$git_dir"
        git_dir=${git_dir#gitdir: }
        [[ $git_dir = /* ]] || git_dir="$PWD/$git_dir"
    fi
    [[ -r $git_dir/HEAD ]] || return 0
    IFS= read -r head < "$git_dir/HEAD" || return 0
    [[ $head == ref:refs/heads/* ]] && print -r -- "  git:${head#ref:refs/heads/}"
}

puppy_project_module() {
    [[ -f package.json || -f .nvmrc ]] && { print -n '  node'; return; }
    [[ -f pyproject.toml || -f requirements.txt || -d .venv || -d venv ]] && { print -n '  python'; return; }
    [[ -f Cargo.toml ]] && { print -n '  rust'; return; }
    [[ -f go.mod ]] && { print -n '  go'; return; }
    [[ -f pom.xml || -f build.gradle || -f build.gradle.kts ]] && { print -n '  java'; return; }
}

puppy_preexec() { printf '\033]133;C\007'; }
puppy_precmd() {
    local status=$? elapsed=$((SECONDS - ${PUPPY_COMMAND_STARTED:-SECONDS})) branch module
    printf '\033]133;D;%s\007\033]133;A\007' "$status"
    branch=$(puppy_git_branch); module=$(puppy_project_module)
    (( elapsed >= 2 )) && PUPPY_DURATION="  ${elapsed}s" || PUPPY_DURATION=
    PUPPY_COMMAND_STARTED=$SECONDS
    if (( status == 0 )); then PUPPY_STATUS_COLOR=green; else PUPPY_STATUS_COLOR=red; fi
    PROMPT="%F{$PUPPY_STATUS_COLOR}%~${branch}${module}${PUPPY_DURATION}%f"$'\n❯ '
    PROMPT+=$'%{\e]133;B\a%}'
}
add-zsh-hook preexec puppy_preexec
add-zsh-hook precmd puppy_precmd
