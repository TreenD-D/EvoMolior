if [ -n "${TMPDIR:-}" ]; then
    # macOS JVM defaults do not follow the shell's TMPDIR. Bind JVM scratch
    # explicitly; an operator -D option later in the argument list still wins.
    set -- "-Djava.io.tmpdir=$TMPDIR" "$@"
fi

if [ -z "$SNAPSHOT_DIR" ]; then
    exec "$JAVACMD" "$@"
fi

# Keep the owner shell alive until the JVM and its shutdown hooks have settled.
# POSIX asynchronous commands inherit ignored SIGINT, so translate the owner's
# INT into TERM for JVM shutdown. Preserve the owner's conventional INT status.
snapshot_child=
snapshot_signal=
snapshot_signal_status=
snapshot_forward_signal() {
    snapshot_wait_interrupted=1
    snapshot_signal=$1
    snapshot_signal_status=$2
    if [ -n "$snapshot_child" ]; then
        kill -s "$snapshot_signal" "$snapshot_child" 2>/dev/null || :
    fi
}
trap 'snapshot_forward_signal HUP 129' HUP
trap 'snapshot_forward_signal TERM 130' INT
trap 'snapshot_forward_signal TERM 143' TERM
# dash redirects an asynchronous command's fd 0 before applying <&0.
# Save the caller's input on a separate descriptor before spawning.
exec 3<&0
"$JAVACMD" "$@" <&3 3<&- &
snapshot_child=$!
exec 3<&-
# A signal can arrive between installing traps and recording the child PID.
if [ -n "$snapshot_signal" ]; then
    kill -s "$snapshot_signal" "$snapshot_child" 2>/dev/null || :
fi
while :
do
    snapshot_wait_interrupted=
    wait "$snapshot_child"
    snapshot_status=$?
    # A trapped signal interrupts wait before the JVM's shutdown is complete.
    # Reap it before the EXIT trap removes jars that shutdown hooks may load.
    if [ -z "$snapshot_wait_interrupted" ]; then
        break
    fi
done
exit "${snapshot_signal_status:-$snapshot_status}"
