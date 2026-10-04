# puppy shell integration for Fish. Fish keeps its native syntax highlighting,
# command validation, autosuggestions and persistent command history.
if not status is-interactive; or test "$PUPPY_SHELL_INTEGRATION" = 0
    return
end

function puppy_git_branch
    set -l git_dir "$PWD/.git"
    if test -f "$git_dir"
        read -l git_pointer < "$git_dir"
        set git_dir (string replace 'gitdir: ' '' -- "$git_pointer")
        if not string match -q '/*' -- "$git_dir"
            set git_dir "$PWD/$git_dir"
        end
    end
    if test -r "$git_dir/HEAD"
        read -l head < "$git_dir/HEAD"
        if string match -q 'ref:refs/heads/*' -- "$head"
            printf '  git:%s' (string replace 'ref:refs/heads/' '' -- "$head")
        end
    end
end

function puppy_project_module
    if test -f package.json; or test -f .nvmrc
        printf '  node'
    else if test -f pyproject.toml; or test -f requirements.txt; or test -d .venv; or test -d venv
        printf '  python'
    else if test -f Cargo.toml
        printf '  rust'
    else if test -f go.mod
        printf '  go'
    else if test -f pom.xml; or test -f build.gradle; or test -f build.gradle.kts
        printf '  java'
    end
end

function fish_prompt
    set -l status_code $status
    printf '\e]133;D;%s\a\e]133;A\a' $status_code
    set_color brgreen
    if test $status_code -ne 0
        set_color brred
    end
    printf '%s' (prompt_pwd)
    set_color normal
    puppy_git_branch
    puppy_project_module
    printf '\n❯ '
    printf '\e]133;B\a'
end

function puppy_preexec --on-event fish_preexec
    printf '\e]133;C\a'
end
