# MealLog 导出格式 v2.0

本文描述当前代码实际生成的 ZIP 格式。每次导出都附带 `README.txt` 和 `manifest.json`，电脑端分析应先检查 `format_version`。

## 通用约定

| 项目 | 约定 |
| --- | --- |
| CSV 编码 | UTF-8 BOM；Python 使用 `utf-8-sig` |
| 分隔与换行 | 英文逗号、CRLF；每个字段用双引号包围，字段内双引号重复转义 |
| 日期 | `YYYY-MM-DD`，按记录发生时的当地日期保存 |
| 时间 | 带时区偏移的 ISO 8601，例如 `2026-10-01T08:00:00+08:00` |
| 数字 | 小数点为 `.`；不混入显示单位或千位分隔符 |
| 空值 | CSV 为空字符串；原始 JSON 为 `null` |
| 零 | 数字 `0`，与空值严格区分 |
| 单位 | 体重 kg、食物 g、时长 min、能量 kcal |
| 文本 | CSV 可能给公式前缀文本加单引号；JSON 保留原文 |

`date` 不随手机后续时区变化重分配。需要按真实时间比较时，应解析时区偏移，不要只比较时间字符串。

## 明细表

```text
weights.csv
id, date, measured_at, weight_kg, note, created_at, updated_at

meals.csv
id, date, eaten_at, food_name, weight_g, energy_kcal,
photo_paths, note, created_at, updated_at

exercises.csv
id, date, started_at, exercise_name, duration_min, energy_kcal,
met, energy_method, note, created_at, updated_at
```

`id` 是单条记录的唯一编号。`created_at`、`updated_at` 描述录入和修改时间，与实际发生时间区分。

`photo_paths` 是 ZIP 内部相对路径，多张照片以分号分隔。`energy_method` 为 `manual`、`met` 或 `unknown`。MET 记录保留强度和时长，其 `energy_kcal` 字段不会伪装成手动输入值；估算结果在分析表中体现。

## 每日汇总

```text
daily_summary.csv
date, weight_measurement_count, latest_weight_kg, latest_measured_at,
meal_count, known_food_weight_g, food_weight_missing_count,
known_energy_kcal, energy_missing_count, exercise_count, exercise_duration_min
```

该表只生成有实际记录的日期。`known_*` 是已知部分的合计，必须结合 `*_missing_count` 判断；不能把部分合计当成确认完整的全天摄入。

## 当前参数下的试算

```text
analysis.csv
date, status, model_version, record_fingerprint,
height_cm, age, sex, activity_factor, lambda_kcal_per_kg,
weight_kg, weight_measured_at, known_intake_kcal,
missing_meal_energy_count, missing_exercise_energy_count,
bmr_kcal, baseline_kcal, known_extra_exercise_kcal,
total_expenditure_kcal, deficit_kcal, predicted_change_kg,
predicted_next_day_weight_kg
```

`status` 为 `incomplete` 或 `trial_current_settings`。后者表示已有数据足以按当前参数试算，并不证明用户已经记录了全天所有饮食和运动。

`baseline_kcal = bmr_kcal × activity_factor`；总消耗在此之外加上额外运动。`record_fingerprint` 用来发现输入是否变化，不是匿名化机制。

## 逐日与累计预测表

`trend_daily.csv` 与 `cumulative_daily.csv` 使用相同列结构，均覆盖区间每一个日历日：

```text
date, day_index, actual_weight_kg, actual_measured_at, intake_kcal,
bmr_kcal, daily_activity_kcal, known_extra_exercise_kcal,
total_expenditure_kcal, deficit_kcal, predicted_weight_kg,
prediction_mode, prediction_status, source_date, source_weight_kg,
source_deficit_kcal, anchor_record_id, anchor_date, anchor_weight_kg,
anchor_measured_at, blocked_on, error_kg, energy_status
```

`predicted_weight_kg` 是**当前这一行日期**的预测；`analysis.csv` 的 `predicted_next_day_weight_kg` 是当天数据试算出的**下一日**预测，两者相差一天。

| 字段/口径 | 含义 |
| --- | --- |
| `prediction_mode` | `daily_recomputed` 或 `cumulative_recomputed`，均用导出时参数重算 |
| `source_date` | 目标预测的前一日；累计起点行等于起点日期 |
| `source_weight_kg` | 逐日为前一天最后实测；累计为前一天预测；起点为 W₀ |
| `source_deficit_kcal` | 对应来源日的缺口；累计起点为空 |
| `anchor_*` | 仅累计有值；区间内最早有效称重及时间 |
| `blocked_on` | 累计首次收支数据不足的来源日，从其下一行开始标记 |
| `prediction_status` | `predicted` / `missing_source` / `no_anchor` / `before_anchor` / `anchor` / `blocked` |
| `energy_status` | `incomplete` 或 `trial_current_settings` |
| `error_kg` | 预测减实测，正负号保留；无配对时为空，累计起点也为空 |

`day_index` 从 1 开始；`daily_activity_kcal = BMR × (activity_factor − 1)`。`known_extra_exercise_kcal` 没有运动记录时为空，但试算不添加额外消耗；记录了无法计算的运动时，总消耗和缺口留空。

逐日预测：`W预测[d+1] = W实际[d] − D[d]/λ`，不依赖历史快照。只能使用紧邻前一日，不跨漏记日期借值。

累计预测：起点取区间内最早有效称重 W₀；之后 BMR 与 MET 只使用递推体重。手动运动热量及饮食热量取记录。后续实测不影响轨迹，漏称不打断预测；缺少必要收支输入会中断后续轨迹，不填零、不自行重置起点。起点行的实际值是 W₀，其余日期取最后实测。

## 有向误差与历史快照

`prediction_errors.csv` 与 `cumulative_errors.csv` 只包含有效配对：

```text
date, actual_record_id, actual_measured_at, actual_weight_kg,
prediction_mode, source_date, source_weight_kg, source_deficit_kcal,
predicted_weight_kg, error_kg
```

`error_kg = W预测 − W实测`，单位 kg，**不取绝对值，不除以体重或变化量**。累计起点不计入误差。图表、长图、CSV 使用同一数据源和符号定义。两种模式的误差不能混合解释：一个每天从实测重启，一个只从 W₀ 出发。

历史快照格式保持不变，完全保留以前保存的参数与结果：

```text
prediction_snapshots.csv
id, date, generated_at, mode, model_version, record_fingerprint,
height_cm, age, sex, activity_factor, lambda_kcal_per_kg,
weight_kg, weight_measured_at, intake_kcal, bmr_kcal, baseline_kcal,
extra_exercise_kcal, total_expenditure_kcal, deficit_kcal,
predicted_change_kg, predicted_next_day_weight_kg
```

快照表只包含所选日期内的历史档案；不再驱动趋势预测。若需要独立研究事前保存的预测，可自行分析 `generated_at` 和 `mode`。不要将当前参数重算的图表结果当成事前冻结的预测。

## v1.2 → v2.0

- 移除趋势的 `saved_prediction_kg`、`prediction_snapshot_id`，改为自动计算的 `predicted_weight_kg` 及明确来源字段。
- 误差表移除快照来源字段、`relative_error_percent`，改为预测模式和输入来源；`error_kg` 始终保留正负。
- 新增累计表与 `prediction_context.json`；原始明细、历史快照、照片不改结构。
- 电脑端应检查 `manifest.format_version` 后选择相应解析逻辑，避免把新版重算结果当成旧版事前预测。

## JSON 与照片

- `records.json`：记录数组，含 `kind`、`occurred_at`、`date`、`amount`、`amount_unit`、`energy_kcal`、`met`、照片路径列表及时间、备注等原始字段。
- `model_settings.json`：导出时当前参数，含 `confirmed` 和额外运动记账约定。
- `manifest.json`：格式 `diet-records`、版本 `2.0`、起止日期、导出时刻、记录/照片/快照数量、额外上下文记录数及预测/误差口径。
- `prediction_context.json`：`previous_day_records` 保存选区前一日的计算输入（id、kind、date、occurred_at、amount、energy_kcal、met），不含照片/备注，不计入选区总量。体重 kg、食物 g、运动 min。结合 `records.json` 和 `model_settings.json` 可复算首日逐日预测。
- `photos/`：应用内保存的 JPEG 副本，最大边 2048 像素。

导出会检查引用照片是否存在；缺失时提示错误，不静默生成丢照片的包。

## 电脑端读取示例

下面使用 Python 标准库直接读取 ZIP，无需先解压。`archive` 换成本地导出包路径。

```python
import csv
import io
import json
from zipfile import ZipFile

archive = "MealLog-export.zip"

with ZipFile(archive) as z:
    manifest = json.loads(z.read("manifest.json").decode("utf-8"))
    if manifest["format_version"] != "2.0":
        raise ValueError("请先核对该版本的导出格式")

    text = z.read("trend_daily.csv").decode("utf-8-sig")
    days = list(csv.DictReader(io.StringIO(text, newline="")))

    def optional_number(value):
        return None if value == "" else float(value)

    for day in days:
        weight = optional_number(day["actual_weight_kg"])
        deficit = optional_number(day["deficit_kcal"])
        print(day["date"], weight, deficit)
```

分析时保留缺失值，不要直接 `fillna(0)`。结合测量时间、缺项计数、起点/断点、预测模式及当前模型参数，判断某一天是否适合参与比较。当前 ZIP 用于导出和外部分析，应用尚无导入恢复功能。
