import functools
import operator
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Callable, Optional, ParamSpec, Self, TypeVar, cast

import pandas as pd

P = ParamSpec("P")
R = TypeVar("R")

EMPTY_TIMESTAMP = "00:00:00"

def cache(func: Callable[P, R]) -> Callable[P, R]:
    result: Optional[R] = None

    @functools.wraps(func)
    def wrapper(*args: P.args, **kwargs: P.kwargs) -> R:
        nonlocal result
        if result is None:
            result = func(*args, **kwargs)
        return result

    return wrapper


@dataclass(frozen=True, eq=True)
class DateRange:
    start_date: date
    end_date: date

    @classmethod
    def try_parse(cls, s: str) -> Optional[Self]:
        parts = s.split("_")
        if len(parts) != 5:
            return None

        *_, start_str, _, end_str = parts
        start_date = date.fromisoformat(start_str)
        end_date = date.fromisoformat(end_str)
        return cls(start_date, end_date)


@cache
def parse_ed_analytics_csv(path: Path) -> pd.DataFrame:
    analytics_df = pd.read_csv(path)
    analytics_df.columns = analytics_df.columns.str.lower()
    analytics_df = analytics_df[analytics_df["role"] == "student"]
    analytics_df = analytics_df.rename(columns={"sis id": "sid"})
    return analytics_df


@cache
def parse_canvas_users_csv(path: Path) -> pd.DataFrame:
    users_df = pd.read_csv(path)
    users_df = users_df.rename(columns={"studentNumber": "sid"})
    return users_df[["sid", "name", "unikey", "inactive"]]


def concatenate_edstem_analytics(folder_path: Path) -> pd.DataFrame:
    analytics_df = parse_ed_analytics_csv(folder_path / "analytics.csv")
    base = analytics_df.set_index("name")[["sid"]]

    dfs: list[pd.DataFrame] = []
    for path in folder_path.glob("*.csv"):
        if path.name == "analytics.csv":
            continue

        date_range = path.stem
        df = pd.read_csv(path)
        df = df.rename(
            columns=lambda name: f"ed_{name}_{date_range}" if name != "name" else name
        )
        df = df.set_index("name")
        dfs.append(df)

    metrics = pd.concat(dfs, axis=1)
    result = base.join(metrics, how="left")

    def df_sort_key(column: str) -> tuple[Any, ...]:
        if (date_range := DateRange.try_parse(column)) is not None:
            return (1, date_range.start_date, date_range.end_date)
        return (0, column)

    sorted_columns = sorted(result.columns, key=df_sort_key)
    return result[sorted_columns].set_index("sid")


def concatenate_user_analytics(folder_path: Path):
    users_df = parse_canvas_users_csv(folder_path / "users.csv")

    def parse_user_analytics_csv(path: Path) -> pd.DataFrame:
        df = pd.read_csv(path)
        df.columns = df.columns.str.lower()
        df["week starting"] = pd.to_datetime(
            df["week starting"], format="mixed", dayfirst=True
        )
        long = df.melt(id_vars="week starting", var_name="metric", value_name="value")

        long["col"] = (
            long["week starting"].dt.strftime("%Y-%m-%d")  # type: ignore
            + "_"
            + long["metric"].str.lower().str.replace(" ", "_")
        )

        wide = long.pivot_table(
            index=None,
            columns="col",
            values="value",
            aggfunc="first",  # type: ignore
        )
        wide.columns = wide.columns.tolist()
        wide = wide.rename(columns=lambda name: f"canvas_{name}")

        wide["unikey"] = path.stem
        int_cols = wide.select_dtypes(include="float").columns
        wide[int_cols] = wide[int_cols].astype("Int64")

        def df_sort_key(column: str) -> tuple[Any, ...]:
            parts = column.split("_")
            if len(parts) == 1:
                return (0, column)
            _, week_starting, *_ = parts
            return (1, date.fromisoformat(week_starting))

        result = wide.merge(users_df[["unikey", "sid"]], on="unikey", how="left")
        sorted_columns = sorted(result.columns, key=df_sort_key)
        result = result[sorted_columns]
        return result

    dfs = list(map(parse_user_analytics_csv, folder_path.glob("*[0-9]*.csv")))
    df = pd.concat(dfs, ignore_index=True)
    int_cols = df.select_dtypes(include="float").columns
    df[int_cols] = df[int_cols].astype("Int64")
    return df.set_index("sid")


def concatenate_echo_video_analytics(folder_path: Path, edstem_path: Path):
    analytics_df = parse_ed_analytics_csv(edstem_path / "analytics.csv")
    base = analytics_df.set_index("email")[["sid"]]

    columns = ["media name", "user email", "duration", "total views", "total view time"]
    column_mapping = dict(
        zip(columns, map(lambda s: "_".join(s.split()), columns))
    )

    def format_media_name(media_name: str) -> str:
        return f"[{media_name}]"

    dfs: list[tuple[pd.Timestamp, pd.DataFrame]] = []

    for path in folder_path.glob("media*.csv"):
        df = pd.read_csv(path)
        df.columns = df.columns.str.lower()
        df = df[columns].rename(columns=column_mapping)

        df = df[~pd.isna(df[column_mapping["user email"]])]
        media_name: str = df[column_mapping["media name"]].iloc[0]
        duration: str = df[column_mapping["duration"]].iloc[0]

        first_create_date = cast(str, pd.read_csv(path, usecols=["Create Date"]).iloc[0, 0])
        create_date = pd.to_datetime(first_create_date)

        if df[column_mapping["user email"]].duplicated().any():
            df = df.groupby(column_mapping["user email"], as_index=False).agg(
                {
                    column_mapping["duration"]: "first",
                    column_mapping["total views"]: "sum",
                    column_mapping["total view time"]: lambda view_times: str(
                        sum(
                            (
                                (pd.to_timedelta(view_time) if isinstance(view_time, str) else pd.to_timedelta(seconds=view_time)) 
                                for view_time in view_times if pd.notna(view_time)
                            ),
                            pd.to_timedelta("0s")
                        )
                    ).split()[-1]
                }
            )

        df = df.rename(columns={column_mapping["user email"]: "email"})
        df = df.set_index("email")
        df = df.sort_values(by="email")
        df = df.reindex(base.index)

        df[column_mapping["duration"]] = df[column_mapping["duration"]].replace(pd.NA, duration)
        df[column_mapping["total views"]] = df[column_mapping["total views"]].fillna(0).astype("Int64")
        df[column_mapping["total view time"]] = df[column_mapping["total view time"]].fillna(EMPTY_TIMESTAMP)

        df = df.rename(
            columns=lambda column: f"echo_{format_media_name(media_name)}_{column}"
            if column != column_mapping["user email"] else column
        )

        dfs.append((create_date, df))

    dfs_sorted = [df for _, df in sorted(dfs, key=operator.itemgetter(0))]

    metrics = pd.concat(dfs_sorted, axis=1)
    return base.join(metrics, how="left").set_index("sid")


def main():
    overwrite = True
    edstem_path = Path("edstem")
    analytics_path = Path("analytics")
    videos_path = Path("echo")

    tables_folder = Path("tables")
    tables_folder.mkdir(parents=True, exist_ok=True)

    edstem_table = concatenate_edstem_analytics(edstem_path)
    analytics_table = concatenate_user_analytics(analytics_path)
    video_table = concatenate_echo_video_analytics(videos_path, edstem_path)

    tables_by_filename = {
        "edstem_table.csv": edstem_table,
        "analytics_table.csv": analytics_table,
        "echo360_video_table.csv": video_table,
    }

    for filename, df in tables_by_filename.items():
        path = tables_folder / filename
        if not path.exists() or overwrite:
            df.to_csv(path)

    joined_path = tables_folder / "data.csv"
    if not joined_path.exists() or overwrite:
        joined = edstem_table.join(analytics_table, on="sid", how="outer")
        joined = joined.join(video_table, on="sid", how="outer")
        joined = joined.set_index("sid")
        joined.to_csv(joined_path)

if __name__ == "__main__":
    main()