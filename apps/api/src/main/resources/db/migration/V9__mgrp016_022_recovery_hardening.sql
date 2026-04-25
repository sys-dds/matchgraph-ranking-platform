alter table ranking_decision_logs
    drop constraint if exists ranking_decision_logs_decision_type_check;

alter table ranking_decision_logs
    add constraint ranking_decision_logs_decision_type_check check (
        decision_type in ('RANKING_RUN', 'FEED_REFRESH', 'REPLAY', 'SCALE_BENCHMARK')
    );
