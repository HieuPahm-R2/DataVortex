# Local development warehouse and budget/KPI policy

Accepted by the owner on 2026-10-16: cloud billing is unavailable, so a separate
local warehouse may serve development analytics/report/forecast; BigQuery remains
the cloud authority. Local results must carry their actual storage provenance and
published watermark, and must not be reported as successful GCS/BigQuery execution.
This explicitly extends the former test-only restriction in architecture/ADR 0002.

The owner also accepted KPI metrics SPEND, CONVERSIONS, CPA, ROAS; operators LTE
and GTE; target > 0; minSamples >= 0; budget currency equal to its account;
nonoverlapping active periods and revision-checked updates. This settles the
whitelist/operator gap in spec sections 7 and 11. Development must remain usable
without a paid cloud service or a live provider credential.
