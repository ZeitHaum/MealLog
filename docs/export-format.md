# MealLog 导出格式 v1.2

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

## 趋势逐日表

```text
trend_daily.csv
date, day_index, actual_weight_kg, actual_measured_at, intake_kcal,
bmr_kcal, daily_activity_kcal, known_extra_exercise_kcal,
total_expenditure_kcal, deficit_kcal, saved_prediction_kg,
prediction_snapshot_id, energy_status
```

该表覆盖选择范围的每一个日历日，`day_index` 从 1 开始。漏记日期保留日期行，测量或计算字段为空。

`daily_activity_kcal = BMR × (activity_factor − 1)`，便于把总消耗分解为基础代谢、日常活动增量和额外运动。

`saved_prediction_kg` 是**对当前这一行日期**的有效事前预测；`analysis.csv` 的 `predicted_next_day_weight_kg` 则是用该行当天数据试算出的**下一日**预测，两者不要混淆。

## 预测快照与误差

```text
prediction_snapshots.csv
id, date, generated_at, mode, model_version, record_fingerprint,
height_cm, age, sex, activity_factor, lambda_kcal_per_kg,
weight_kg, weight_measured_at, intake_kcal, bmr_kcal, baseline_kcal,
extra_exercise_kcal, total_expenditure_kcal, deficit_kcal,
predicted_change_kg, predicted_next_day_weight_kg

prediction_errors.csv
date, actual_record_id, actual_measured_at, actual_weight_kg,
snapshot_id, snapshot_generated_at, model_version,
predicted_weight_kg, error_kg, relative_error_percent
```

误差定义为 `(预测 − 实测) / 实测 × 100`，不是相对体重变化量的误差。快照 `date` 是源日期，误差 `date` 是其下一日的目标日期。

仅 `same_day_estimate` 且在源日期当天保存的有效快照用于配对，并要求保存时刻早于目标日任一次称重或体重录入时刻。每个目标日最多一对，取最后一个符合条件的快照，以及当天最后一条实测体重。没有实测或预测时不生成误差行。

导出范围开始日的预测可能来自范围前一日；这份必要快照也会包含在快照表中，方便复核来源。

## JSON 与照片

- `records.json`：记录数组，含 `kind`、`occurred_at`、`date`、`amount`、`amount_unit`、`energy_kcal`、`met`、照片路径列表及时间、备注等原始字段。
- `model_settings.json`：导出时当前参数，含 `confirmed` 和额外运动记账约定。
- `manifest.json`：格式 `diet-records`、版本 `1.2`、起止日期、导出时刻、记录/照片/快照数量。
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
    if manifest["format_version"] != "1.2":
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

分析时保留缺失值，不要直接 `fillna(0)`。可以结合测量时间、缺项计数、快照生成时间和模型参数变化，判断某一天是否适合参与比较。当前 ZIP 用于导出和外部分析，应用尚无导入恢复功能。
