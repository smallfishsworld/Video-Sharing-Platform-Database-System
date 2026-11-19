# Adds up the "Trigger" lines of EXPLAIN ANALYZE output per execution (an execution starts at a
# line beginning with "== "): derived-table triggers (derived_*), foreign-key triggers
# ("for constraint ..."), other triggers, and the execution time. See delete-costs.sql.
/^== / { head = substr($0, 4); next }
/^ *Trigger / {
    t = $0; sub(/.*time=/, "", t); sub(/ .*/, "", t)
    if ($0 ~ /Trigger derived_/) d += t
    else if ($0 ~ /Trigger for constraint/) f += t
    else o += t
    next
}
/^ *Execution Time:/ {
    printf "%-48s execution %8.3f ms   derived_* %7.3f   foreign keys %7.3f   other %6.3f\n", head, $3, d, f, o
    d = f = o = 0
}
