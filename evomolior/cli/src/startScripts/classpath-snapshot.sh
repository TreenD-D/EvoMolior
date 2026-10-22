# Snapshot the installed classpath before launching. Only this invocation owns
# this directory; never sweep other invocations' snapshots.
SNAPSHOT_DIR=
if [ "${EVOMOLIOR_DISABLE_CLASSPATH_SNAPSHOT:-}" != "1" ] && ! "$cygwin" && ! "$msys" ; then
    SNAPSHOT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/evomolior-cli-classpath-XXXXXXXX") ||
        die "Could not create classpath snapshot directory"
    trap 'rm -rf -- "$SNAPSHOT_DIR"' 0
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
    mkdir "$SNAPSHOT_DIR/lib" || die "Could not create classpath snapshot lib directory"
    SNAPSHOT_CLASSPATH=
    # Split only on colons, not whitespace or wildcard characters in jar paths.
    # Parameter expansion avoids changing the launcher's IFS or globbing state.
    snapshot_remaining=$CLASSPATH
    while :
    do
        classpath_entry=${snapshot_remaining%%:*}
        case $classpath_entry in
            "$APP_HOME"/lib/*.jar)
                jar_name=${classpath_entry##*/}
                cp -p "$classpath_entry" "$SNAPSHOT_DIR/lib/$jar_name" ||
                    die "Could not snapshot jar: $classpath_entry"
                classpath_entry=$SNAPSHOT_DIR/lib/$jar_name
                ;;
        esac
        SNAPSHOT_CLASSPATH=$SNAPSHOT_CLASSPATH$classpath_entry
        case $snapshot_remaining in
            *:*) snapshot_remaining=${snapshot_remaining#*:}
                 SNAPSHOT_CLASSPATH=$SNAPSHOT_CLASSPATH: ;;
            *) break ;;
        esac
    done
    CLASSPATH=$SNAPSHOT_CLASSPATH
fi
