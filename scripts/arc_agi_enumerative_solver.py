#!/usr/bin/env python3
"""Bounded, deterministic ARC-AGI-2 enumerative fallback solver.

The public entry point is :func:`build_task_solvers`.  It deliberately accepts
only visible demonstration pairs and returns exactly two one-grid callables.
The search is a small typed program portfolio: every candidate is a finite
transformation family, and learned parameters are inferred from the supplied
pairs only.  This makes the module useful as a cheap fallback without making
the solver dependent on provider artifacts, packages, or network access.
"""

import argparse
from dataclasses import dataclass
from itertools import product
from typing import Callable, Iterable, Optional, Sequence


Grid = tuple[tuple[int, ...], ...]
Demo = tuple[Grid, Grid]
Transform = Callable[[Grid], Grid]

_MAX_TRANSLATION = 3
_MAX_CANDIDATES = 420
_COLORS = tuple(range(10))

__all__ = ["build_task_solvers", "run_self_tests"]


def _grid(value: object) -> Grid:
    """Normalize a rectangular ARC grid to an immutable typed value."""
    if not isinstance(value, (list, tuple)):
        return ()
    rows: list[tuple[int, ...]] = []
    width: Optional[int] = None
    for row in value:
        if not isinstance(row, (list, tuple)):
            return ()
        normalized = tuple(int(cell) for cell in row)
        if width is None:
            width = len(normalized)
        if len(normalized) != width:
            return ()
        rows.append(normalized)
    return tuple(rows)


def _out(value: Grid) -> list[list[int]]:
    """Return a fresh mutable grid for a solver caller."""
    return [list(row) for row in value]


def _shape(value: Grid) -> tuple[int, int]:
    """Return grid height and width."""
    return len(value), len(value[0]) if value else 0


def _valid(value: object) -> bool:
    """Return whether a candidate result is a rectangular ARC grid."""
    if not isinstance(value, tuple):
        return False
    if not value:
        return True
    width = len(value[0])
    return all(isinstance(row, tuple) and len(row) == width for row in value)


def _colors(value: Grid) -> set[int]:
    """Return colors occurring in a grid."""
    return {cell for row in value for cell in row}


def _background(value: Grid) -> int:
    """Choose a deterministic background, preferring ARC's conventional zero."""
    if 0 in _colors(value):
        return 0
    counts: dict[int, int] = {}
    for row in value:
        for cell in row:
            counts[cell] = counts.get(cell, 0) + 1
    return min(counts, key=lambda color: (-counts[color], color), default=0)


def _same_shape(left: Grid, right: Grid) -> bool:
    """Return whether two grids have equal dimensions."""
    return _shape(left) == _shape(right)


def _identity(value: Grid) -> Grid:
    """Return the input unchanged."""
    return value


def _rotate90(value: Grid) -> Grid:
    """Rotate a grid clockwise."""
    return tuple(tuple(row[column] for row in reversed(value)) for column in range(len(value[0]))) if value else ()


def _rotate180(value: Grid) -> Grid:
    """Rotate a grid by 180 degrees."""
    return tuple(tuple(reversed(row)) for row in reversed(value))


def _rotate270(value: Grid) -> Grid:
    """Rotate a grid counter-clockwise."""
    return _rotate180(_rotate90(value))


def _flip_horizontal(value: Grid) -> Grid:
    """Reflect a grid across its horizontal midline."""
    return tuple(reversed(value))


def _flip_vertical(value: Grid) -> Grid:
    """Reflect a grid across its vertical midline."""
    return tuple(tuple(reversed(row)) for row in value)


def _transpose(value: Grid) -> Grid:
    """Transpose a grid."""
    return tuple(tuple(row[column] for row in value) for column in range(len(value[0]))) if value else ()


def _anti_transpose(value: Grid) -> Grid:
    """Reflect across the anti-diagonal."""
    return _rotate180(_transpose(value))


def _anti_diagonal_reflection(value: Grid) -> Grid:
    """Reflect across the anti-diagonal using the dihedral convention."""
    return _transpose(_rotate180(value))


def _crop(value: Grid, top: int, left: int, bottom: int, right: int) -> Grid:
    """Crop an inclusive rectangle, returning empty for an invalid rectangle."""
    if not value or top < 0 or left < 0 or bottom <= top or right <= left:
        return ()
    if bottom > len(value) or right > len(value[0]):
        return ()
    return tuple(row[left:right] for row in value[top:bottom])


def _bbox(value: Grid, predicate: Callable[[int], bool]) -> Optional[tuple[int, int, int, int]]:
    """Find the half-open bounding box of cells satisfying ``predicate``."""
    points = [
        (row_index, column_index)
        for row_index, row in enumerate(value)
        for column_index, cell in enumerate(row)
        if predicate(cell)
    ]
    if not points:
        return None
    rows, columns = zip(*points)
    return min(rows), min(columns), max(rows) + 1, max(columns) + 1


def _crop_bbox(value: Grid, predicate: Callable[[int], bool]) -> Grid:
    """Crop to cells selected by a color predicate."""
    bounds = _bbox(value, predicate)
    return _crop(value, *bounds) if bounds else ()


def _components(value: Grid, *, background: Optional[int] = None) -> list[tuple[int, frozenset[tuple[int, int]]]]:
    """Return four-connected monochromatic components in stable scan order."""
    if not value:
        return []
    bg = _background(value) if background is None else background
    height, width = _shape(value)
    unseen = {
        (row, column)
        for row in range(height)
        for column in range(width)
        if value[row][column] != bg
    }
    result: list[tuple[int, frozenset[tuple[int, int]]]] = []
    while unseen:
        start = min(unseen)
        color = value[start[0]][start[1]]
        stack = [start]
        unseen.remove(start)
        component: set[tuple[int, int]] = set()
        while stack:
            row, column = stack.pop()
            component.add((row, column))
            for neighbor in ((row - 1, column), (row + 1, column), (row, column - 1), (row, column + 1)):
                if neighbor in unseen and value[neighbor[0]][neighbor[1]] == color:
                    unseen.remove(neighbor)
                    stack.append(neighbor)
        result.append((color, frozenset(component)))
    return result


def _component_bbox(component: frozenset[tuple[int, int]]) -> tuple[int, int, int, int]:
    """Return a component's half-open bounding box."""
    rows, columns = zip(*component)
    return min(rows), min(columns), max(rows) + 1, max(columns) + 1


def _paint(value: Grid, points: Iterable[tuple[int, int]], color: int) -> Grid:
    """Paint in-bounds points on a copied grid."""
    result = [list(row) for row in value]
    for row, column in points:
        if 0 <= row < len(result) and 0 <= column < len(result[0]):
            result[row][column] = color
    return tuple(tuple(row) for row in result)


def _shift(value: Grid, row_delta: int, column_delta: int, background: Optional[int] = None) -> Grid:
    """Translate all non-background cells on a fresh background."""
    if not value:
        return value
    bg = _background(value) if background is None else background
    result = [[bg for _ in row] for row in value]
    for row_index, row in enumerate(value):
        for column_index, cell in enumerate(row):
            if cell == bg:
                continue
            target_row = row_index + row_delta
            target_column = column_index + column_delta
            if 0 <= target_row < len(value) and 0 <= target_column < len(row):
                result[target_row][target_column] = cell
    return tuple(tuple(row) for row in result)


def _union_shift(value: Grid, row_delta: int, column_delta: int, background: Optional[int] = None) -> Grid:
    """Copy non-background cells by a bounded translation and union the copies."""
    if not value:
        return value
    bg = _background(value) if background is None else background
    points = [
        (row_index, column_index, cell)
        for row_index, row in enumerate(value)
        for column_index, cell in enumerate(row)
        if cell != bg
    ]
    result = [list(row) for row in value]
    for row, column, cell in points:
        target_row, target_column = row + row_delta, column + column_delta
        if 0 <= target_row < len(value) and 0 <= target_column < len(value[0]):
            result[target_row][target_column] = cell
    return tuple(tuple(row) for row in result)


def _complete_axis(value: Grid, axis: str, background: Optional[int] = None) -> Grid:
    """Complete a horizontal or vertical reflection into background cells."""
    if not value:
        return value
    bg = _background(value) if background is None else background
    height, width = _shape(value)
    result = [list(row) for row in value]
    for row in range(height):
        for column in range(width):
            mirror = (row, width - 1 - column) if axis == "vertical" else (height - 1 - row, column)
            source_row, source_column = mirror
            if result[row][column] == bg and value[source_row][source_column] != bg:
                result[row][column] = value[source_row][source_column]
    return tuple(tuple(row) for row in result)


def _complete_diagonal(value: Grid, main: bool = True, background: Optional[int] = None) -> Grid:
    """Complete diagonal symmetry for square grids."""
    if not value or len(value) != len(value[0]):
        return value
    bg = _background(value) if background is None else background
    result = [list(row) for row in value]
    size = len(value)
    for row in range(size):
        for column in range(size):
            mirror = (column, row) if main else (size - 1 - column, size - 1 - row)
            source_row, source_column = mirror
            if result[row][column] == bg and value[source_row][source_column] != bg:
                result[row][column] = value[source_row][source_column]
    return tuple(tuple(row) for row in result)


def _map_grid(value: Grid, mapping: dict[int, int]) -> Grid:
    """Apply a total-on-observed-colors relabeling, leaving other colors intact."""
    return tuple(tuple(mapping.get(cell, cell) for cell in row) for row in value)


def _infer_color_map(demos: Sequence[Demo]) -> Optional[dict[int, int]]:
    """Infer one globally consistent cell-color mapping."""
    mapping: dict[int, int] = {}
    for source, target in demos:
        if not _same_shape(source, target):
            return None
        for source_row, target_row in zip(source, target):
            for source_cell, target_cell in zip(source_row, target_row):
                prior = mapping.get(source_cell)
                if prior is not None and prior != target_cell:
                    return None
                mapping[source_cell] = target_cell
    return mapping


def _infer_recolor_target(
    demos: Sequence[Demo],
    selector: str,
) -> Optional[tuple[int, int]]:
    """Infer a stable source and target color for one selected object."""
    source_color: Optional[int] = None
    target_color: Optional[int] = None
    for source, target in demos:
        if not _same_shape(source, target):
            return None
        components = _components(source)
        if not components:
            return None
        ordered = sorted(
            components,
            key=lambda item: (-len(item[1]), item[0], min(item[1])),
        )
        if selector == "largest":
            chosen = ordered[0]
        elif selector == "smallest":
            chosen = ordered[-1]
        elif selector == "top_left":
            chosen = min(components, key=lambda item: min(item[1]))
        else:
            return None
        color, points = chosen
        changed = {target[row][column] for row, column in points}
        if len(changed) != 1:
            return None
        proposed_target = next(iter(changed))
        for row, column in points:
            if target[row][column] != proposed_target:
                return None
        outside = [
            target[row][column] == source[row][column]
            for row in range(len(source))
            for column in range(len(source[0]))
            if (row, column) not in points
        ]
        if not all(outside):
            return None
        if source_color is not None and source_color != color:
            return None
        if target_color is not None and target_color != proposed_target:
            return None
        source_color, target_color = color, proposed_target
    return (source_color, target_color) if source_color is not None and target_color is not None else None


def _infer_object_area_map(demos: Sequence[Demo]) -> Optional[dict[int, int]]:
    """Infer a color assignment based on each component's area."""
    mapping: dict[int, int] = {}
    for source, target in demos:
        if not _same_shape(source, target):
            return None
        components = _components(source)
        for _, points in components:
            output_colors = {target[row][column] for row, column in points}
            if len(output_colors) != 1:
                return None
            output_color = next(iter(output_colors))
            area = len(points)
            if area in mapping and mapping[area] != output_color:
                return None
            mapping[area] = output_color
        for row in range(len(source)):
            for column in range(len(source[0])):
                if source[row][column] == _background(source) and target[row][column] != source[row][column]:
                    return None
    return mapping


def _apply_object_area_map(value: Grid, mapping: dict[int, int]) -> Grid:
    """Recolor each connected object according to its area."""
    result = value
    for _, points in _components(value):
        if len(points) in mapping:
            result = _paint(result, points, mapping[len(points)])
    return result


def _select_component(value: Grid, selector: str, keep: bool) -> Grid:
    """Keep or erase one deterministically selected connected object."""
    components = _components(value)
    if not components:
        return value
    ordered = sorted(
        components,
        key=lambda item: (-len(item[1]), item[0], min(item[1])),
    )
    chosen = ordered[0] if selector == "largest" else ordered[-1]
    chosen_points = set(chosen[1])
    background = _background(value)
    if keep:
        return _paint(
            tuple(tuple(background for _ in row) for row in value),
            chosen_points,
            chosen[0],
        )
    return _paint(value, chosen_points, background)


def _recolor_selected(value: Grid, selector: str, target_color: int) -> Grid:
    """Recolor one deterministically selected component."""
    components = _components(value)
    if not components:
        return value
    ordered = sorted(components, key=lambda item: (-len(item[1]), item[0], min(item[1])))
    chosen = ordered[0] if selector == "largest" else ordered[-1] if selector == "smallest" else min(components, key=lambda item: min(item[1]))
    return _paint(value, chosen[1], target_color)


def _recolor_all(value: Grid, source_color: int, target_color: int) -> Grid:
    """Recolor every cell of one source color."""
    return tuple(
        tuple(target_color if cell == source_color else cell for cell in row)
        for row in value
    )


@dataclass(frozen=True)
class _Program:
    """A fitted transformation and its stable ranking metadata."""

    name: str
    family: str
    mdl: int
    transform: Transform

    def __call__(self, value: object) -> list[list[int]]:
        """Apply the fitted transformation to one grid."""
        try:
            return _out(self.transform(_grid(value)))
        except (IndexError, TypeError, ValueError):
            return []


@dataclass(frozen=True)
class _Spec:
    """A bounded transformation schema that can be fitted to visible demos."""

    name: str
    family: str
    mdl: int
    fit: Callable[[Sequence[Demo]], Optional[_Program]]


@dataclass(frozen=True)
class _Ranked:
    """A fitted candidate plus evidence used for deterministic selection."""

    program: _Program
    exact: int
    loo: int
    shape_valid: int
    color_valid: int
    family_order: int

    @property
    def key(self) -> tuple[int, int, int, int, int, int]:
        """Return the total ordering key, with exact fit as the primary signal."""
        return (
            self.exact,
            self.loo,
            self.shape_valid,
            self.color_valid,
            -self.program.mdl,
            -self.family_order,
        )


def _fixed_spec(name: str, family: str, mdl: int, operation: Transform) -> _Spec:
    """Create a fixed, parameter-free schema that is always applicable."""
    return _Spec(
        name,
        family,
        mdl,
        lambda demos: _Program(name, family, mdl, operation),
    )


def _learned_color_spec() -> _Spec:
    """Create the globally consistent color relabeling schema."""
    def fit(demos: Sequence[Demo]) -> Optional[_Program]:
        mapping = _infer_color_map(demos)
        if mapping is None:
            return None
        return _Program("color_map", "color_map", 3 + len(mapping), lambda value: _map_grid(value, mapping))
    return _Spec("color_map", "color_map", 3, fit)


def _crop_specs() -> list[_Spec]:
    """Enumerate deterministic crop and bounding-box schemas."""
    specs: list[_Spec] = []

    def add(name: str, family: str, operation_for_color: Callable[[Grid, int], Grid]) -> None:
        for color in _COLORS:
            def fit(demos: Sequence[Demo], color: int = color) -> Optional[_Program]:
                if not demos:
                    return None
                if not all(operation_for_color(source, color) == target for source, target in demos):
                    return None
                return _Program(f"{name}_{color}", family, 5, lambda value, color=color: operation_for_color(value, color))
            specs.append(_Spec(f"{name}_{color}", family, 5, fit))

    add("crop_color_bbox", "crop_bbox", lambda value, color: _crop_bbox(value, lambda cell: cell == color))
    add("crop_without_color", "crop_bbox", lambda value, color: _crop_bbox(value, lambda cell: cell != color))

    def non_background(value: Grid) -> Grid:
        return _crop_bbox(value, lambda cell: cell != _background(value))

    specs.append(_Spec("crop_non_background_bbox", "crop_bbox", 4, lambda demos: _fit_fixed(demos, non_background, "crop_non_background_bbox", "crop_bbox", 4)))

    def largest_bbox(value: Grid, _: int) -> Grid:
        components = _components(value)
        if not components:
            return ()
        component = max(components, key=lambda item: (len(item[1]), -item[0], tuple(sorted(item[1]))))
        return _crop(value, *_component_bbox(component[1]))

    specs.append(_Spec("crop_largest_object_bbox", "crop_bbox", 6, lambda demos: _fit_fixed(demos, largest_bbox, "crop_largest_object_bbox", "crop_bbox", 6)))

    def smallest_bbox(value: Grid, _: int) -> Grid:
        components = _components(value)
        if not components:
            return ()
        component = min(components, key=lambda item: (len(item[1]), item[0], min(item[1])))
        return _crop(value, *_component_bbox(component[1]))

    specs.append(_Spec("crop_smallest_object_bbox", "crop_bbox", 6, lambda demos: _fit_fixed(demos, smallest_bbox, "crop_smallest_object_bbox", "crop_bbox", 6)))
    return specs


def _fit_fixed(
    demos: Sequence[Demo],
    operation: Transform,
    name: str,
    family: str,
    mdl: int,
) -> Optional[_Program]:
    """Fit a fixed operation only when it exactly explains every visible pair."""
    if not demos or not all(operation(source) == target for source, target in demos):
        return None
    return _Program(name, family, mdl, operation)


def _object_specs() -> list[_Spec]:
    """Enumerate object-level recoloring schemas."""
    specs: list[_Spec] = []
    for selector in ("largest", "smallest"):
        for keep in (True, False):
            verb = "keep" if keep else "remove"
            name = f"{verb}_{selector}_object"
            operation = lambda value, selector=selector, keep=keep: _select_component(
                value,
                selector,
                keep,
            )
            specs.append(
                _Spec(
                    name,
                    "object_filter",
                    6,
                    lambda demos, operation=operation, name=name: _fit_fixed(
                        demos,
                        operation,
                        name,
                        "object_filter",
                        6,
                    ),
                )
            )

    for selector in ("largest", "smallest", "top_left"):
        def fit(demos: Sequence[Demo], selector: str = selector) -> Optional[_Program]:
            target = _infer_recolor_target(demos, selector)
            if target is None:
                return None
            _, target_color = target
            return _Program(
                f"recolor_{selector}_{target_color}",
                "object_recolor",
                7,
                lambda value, selector=selector, target_color=target_color: _recolor_selected(value, selector, target_color),
            )
        specs.append(_Spec(f"recolor_{selector}", "object_recolor", 7, fit))

    area_spec = _Spec(
        "recolor_by_object_area",
        "object_recolor",
        8,
        lambda demos: (
            _Program(
                "recolor_by_object_area",
                "object_recolor",
                8 + len(mapping),
                lambda value, mapping=mapping: _apply_object_area_map(value, mapping),
            )
            if (mapping := _infer_object_area_map(demos)) is not None
            else None
        ),
    )
    specs.append(area_spec)

    for source_color, target_color in product(_COLORS, repeat=2):
        if source_color == target_color:
            continue
        def fit(
            demos: Sequence[Demo],
            source_color: int = source_color,
            target_color: int = target_color,
        ) -> Optional[_Program]:
            operation = lambda value: _recolor_all(value, source_color, target_color)
            return _fit_fixed(demos, operation, f"recolor_color_{source_color}_{target_color}", "object_recolor", 5)
        specs.append(_Spec(f"recolor_color_{source_color}_{target_color}", "object_recolor", 5, fit))
    return specs


def _translation_specs() -> list[_Spec]:
    """Enumerate bounded shifts, copy-unions, and selected-object shifts."""
    specs: list[_Spec] = []
    deltas = [
        (row_delta, column_delta)
        for row_delta in range(-_MAX_TRANSLATION, _MAX_TRANSLATION + 1)
        for column_delta in range(-_MAX_TRANSLATION, _MAX_TRANSLATION + 1)
        if (row_delta, column_delta) != (0, 0)
    ]
    for row_delta, column_delta in deltas:
        for kind, operation in (
            ("shift", lambda value, rd=row_delta, cd=column_delta: _shift(value, rd, cd)),
            ("copy", lambda value, rd=row_delta, cd=column_delta: _union_shift(value, rd, cd)),
        ):
            name = f"{kind}_{row_delta}_{column_delta}"
            specs.append(_Spec(name, "translation_copy", 5, lambda demos, operation=operation, name=name: _fit_fixed(demos, operation, name, "translation_copy", 5)))
        for selector in ("largest", "smallest"):
            def selected_shift(
                value: Grid,
                selector: str = selector,
                row_delta: int = row_delta,
                column_delta: int = column_delta,
            ) -> Grid:
                components = _components(value)
                if not components:
                    return value
                ordered = sorted(components, key=lambda item: (-len(item[1]), item[0], min(item[1])))
                chosen = ordered[0] if selector == "largest" else ordered[-1]
                bg = _background(value)
                result = [[bg for _ in row] for row in value]
                selected_points = set(chosen[1])
                for row, column in (
                    (row_index, column_index)
                    for row_index, row in enumerate(value)
                    for column_index, cell in enumerate(row)
                    if (row_index, column_index) not in selected_points and cell != bg
                ):
                    result[row][column] = value[row][column]
                for row, column in selected_points:
                    target_row, target_column = row + row_delta, column + column_delta
                    if 0 <= target_row < len(value) and 0 <= target_column < len(value[0]):
                        result[target_row][target_column] = value[row][column]
                return tuple(tuple(row) for row in result)
            name = f"shift_{selector}_{row_delta}_{column_delta}"
            specs.append(_Spec(name, "translation_copy", 7, lambda demos, operation=selected_shift, name=name: _fit_fixed(demos, operation, name, "translation_copy", 7)))
    return specs


def _geometry_specs() -> list[_Spec]:
    """Enumerate the eight dihedral geometry transforms."""
    operations = (
        ("identity", "identity", 1, _identity),
        ("rotate_90", "geometry", 2, _rotate90),
        ("rotate_180", "geometry", 2, _rotate180),
        ("rotate_270", "geometry", 2, _rotate270),
        ("reflect_horizontal", "geometry", 2, _flip_horizontal),
        ("reflect_vertical", "geometry", 2, _flip_vertical),
        ("transpose", "geometry", 2, _transpose),
        ("anti_diagonal_reflection", "geometry", 2, _anti_diagonal_reflection),
    )
    return [
        _fixed_spec(name, family, mdl, operation)
        for name, family, mdl, operation in operations
    ]


def _symmetry_specs() -> list[_Spec]:
    """Enumerate symmetry completion and reflection-union operations."""
    specs: list[_Spec] = []
    operations: list[tuple[str, Transform]] = [
        ("complete_vertical", lambda value: _complete_axis(value, "vertical")),
        ("complete_horizontal", lambda value: _complete_axis(value, "horizontal")),
        ("complete_main_diagonal", lambda value: _complete_diagonal(value, True)),
        ("complete_anti_diagonal", lambda value: _complete_diagonal(value, False)),
        ("symmetrize_vertical", lambda value: _union_shift(_flip_vertical(value), 0, 0)),
    ]
    # The final operation above is intentionally replaced by an explicit
    # reflection union below; retaining this in the list would only duplicate
    # a geometry operation.
    operations[-1] = (
        "union_vertical_reflection",
        lambda value: _merge_non_background(value, _flip_vertical(value)),
    )
    operations.extend(
        [
            ("union_horizontal_reflection", lambda value: _merge_non_background(value, _flip_horizontal(value))),
        ]
    )
    for name, operation in operations:
        specs.append(_Spec(name, "symmetry_completion", 5, lambda demos, operation=operation, name=name: _fit_fixed(demos, operation, name, "symmetry_completion", 5)))
    return specs


def _merge_non_background(left: Grid, right: Grid) -> Grid:
    """Overlay two equal-shaped grids, treating each grid's background as empty."""
    if not _same_shape(left, right):
        return ()
    left_bg, right_bg = _background(left), _background(right)
    result: list[list[int]] = []
    for left_row, right_row in zip(left, right):
        result.append([
            right_cell if left_cell == left_bg and right_cell != right_bg else left_cell
            for left_cell, right_cell in zip(left_row, right_row)
        ])
    return tuple(tuple(row) for row in result)


def _composition_specs() -> list[_Spec]:
    """Enumerate small compositions of fixed and learned primitives."""
    fixed = [
        ("rotate_90", _rotate90),
        ("rotate_180", _rotate180),
        ("rotate_270", _rotate270),
        ("reflect_horizontal", _flip_horizontal),
        ("reflect_vertical", _flip_vertical),
        ("transpose", _transpose),
        ("anti_diagonal_reflection", _anti_diagonal_reflection),
        ("complete_vertical", lambda value: _complete_axis(value, "vertical")),
        ("complete_horizontal", lambda value: _complete_axis(value, "horizontal")),
    ]
    specs: list[_Spec] = []

    for first_name, first in fixed:
        for second_name, second in fixed:
            if first_name == second_name:
                continue
            name = f"compose_{first_name}_{second_name}"
            operation = lambda value, first=first, second=second: second(first(value))
            specs.append(_Spec(name, "composition", 5, lambda demos, operation=operation, name=name: _fit_fixed(demos, operation, name, "composition", 5)))

    color_spec = _learned_color_spec()
    for geometry_name, geometry in fixed[:7]:
        name_after = f"compose_{geometry_name}_then_color_map"
        def fit_after(demos: Sequence[Demo], geometry: Transform = geometry, geometry_name: str = geometry_name, name: str = name_after) -> Optional[_Program]:
            transformed = [(geometry(source), target) for source, target in demos]
            fitted = color_spec.fit(transformed)
            if fitted is None:
                return None
            return _Program(name, "composition", fitted.mdl + 2, lambda value, geometry=geometry, fitted=fitted: fitted.transform(geometry(value)))
        specs.append(_Spec(name_after, "composition", 6, fit_after))

        name_before = f"compose_color_map_then_{geometry_name}"
        def fit_before(demos: Sequence[Demo], geometry: Transform = geometry, name: str = name_before) -> Optional[_Program]:
            fitted = color_spec.fit([(source, geometry(target)) for source, target in demos])
            if fitted is None:
                return None
            return _Program(name, "composition", fitted.mdl + 2, lambda value, geometry=geometry, fitted=fitted: geometry(fitted.transform(value)))
        specs.append(_Spec(name_before, "composition", 6, fit_before))
    return specs


_PORTFOLIO_CACHE: Optional[tuple[_Spec, ...]] = None


def _portfolio() -> tuple[_Spec, ...]:
    """Build the bounded schema portfolio in stable order."""
    global _PORTFOLIO_CACHE
    if _PORTFOLIO_CACHE is None:
        specs = _geometry_specs()
        specs.append(_learned_color_spec())
        specs.extend(_crop_specs())
        specs.extend(_object_specs())
        specs.extend(_translation_specs())
        specs.extend(_symmetry_specs())
        specs.extend(_composition_specs())
        _PORTFOLIO_CACHE = tuple(specs[:_MAX_CANDIDATES])
    return _PORTFOLIO_CACHE


def _normalize_demos(demos: Iterable[object]) -> tuple[Demo, ...]:
    """Normalize tuple- or mapping-shaped visible demonstrations."""
    result: list[Demo] = []
    for item in demos:
        source: object
        target: object
        if isinstance(item, dict):
            source, target = item.get("input"), item.get("output")
        elif isinstance(item, (list, tuple)) and len(item) == 2:
            source, target = item
        else:
            continue
        normalized_source, normalized_target = _grid(source), _grid(target)
        if _valid(normalized_source) and _valid(normalized_target):
            result.append((normalized_source, normalized_target))
    return tuple(result)


def _rank_specs(demos: Sequence[Demo]) -> list[_Ranked]:
    """Fit and score every schema, including an independent visible LOO score."""
    ranked: list[_Ranked] = []
    family_order: dict[str, int] = {}
    for spec in _portfolio():
        family_order.setdefault(spec.family, len(family_order))
        try:
            fitted = spec.fit(demos)
        except (IndexError, TypeError, ValueError, KeyError):
            fitted = None
        if fitted is None:
            continue
        exact = sum(fitted.transform(source) == target for source, target in demos)
        loo = 0
        # A candidate that cannot fit the visible task is only retained as a
        # diversity reserve.  Computing LOO for every such reserve program is
        # needlessly expensive, while identity and geometry remain useful
        # generalization baselines and are always scored.
        if exact == len(demos) or spec.family in {"identity", "geometry"}:
            for holdout_index, holdout in enumerate(demos):
                remaining = demos[:holdout_index] + demos[holdout_index + 1 :]
                if not remaining:
                    continue
                try:
                    loo_program = spec.fit(remaining)
                except (IndexError, TypeError, ValueError, KeyError):
                    loo_program = None
                if loo_program is not None and loo_program.transform(holdout[0]) == holdout[1]:
                    loo += 1
        shape_valid = 0
        color_valid = 0
        for source, _ in demos:
            try:
                prediction = fitted.transform(source)
            except (IndexError, TypeError, ValueError):
                continue
            if _valid(prediction):
                shape_valid += 1
                if all(0 <= cell <= 9 for row in prediction for cell in row):
                    color_valid += 1
        ranked.append(_Ranked(fitted, exact, loo, shape_valid, color_valid, family_order[spec.family]))
    ranked.sort(key=lambda candidate: candidate.key, reverse=True)
    return ranked


def _solver_callable(candidate: _Ranked, ordinal: int) -> Callable[[object], list[list[int]]]:
    """Wrap a ranked program as a named one-grid callable."""
    program = candidate.program

    def solve(value: object) -> list[list[int]]:
        """Apply the selected deterministic ARC transformation."""
        return program(value)

    solve.__name__ = f"arc_{ordinal}_{program.family}_{program.name}"
    solve.__qualname__ = solve.__name__
    solve.family = program.family  # type: ignore[attr-defined]
    solve.program = program.name  # type: ignore[attr-defined]
    solve.score = candidate.key  # type: ignore[attr-defined]
    return solve


def build_task_solvers(demos: Iterable[object]) -> tuple[Callable[[object], list[list[int]]], Callable[[object], list[list[int]]]]:
    """Return exactly two deterministic one-grid solvers fitted to ``demos``.

    The second solver is selected from a different transformation family when
    possible.  Leave-one-out evidence is computed only from visible pairs and
    is never used as an oracle for a held-out task output.
    """
    normalized = _normalize_demos(demos)
    ranked = _rank_specs(normalized)
    if not ranked:
        identity = _Ranked(_Program("identity", "identity", 1, _identity), 0, 0, 0, 0, 0)
        ranked = [identity]
    first = ranked[0]
    exact_required = len(normalized)
    second = next(
        (
            candidate
            for candidate in ranked[1:]
            if candidate.exact == exact_required
            and candidate.program.family != first.program.family
        ),
        None,
    )
    if second is None:
        second = next(
            (
                candidate
                for candidate in ranked[1:]
                if candidate.program.family != first.program.family
            ),
            None,
        )
    if second is None:
        second = ranked[1] if len(ranked) > 1 else first
    return _solver_callable(first, 1), _solver_callable(second, 2)


def run_self_tests() -> None:
    """Run compact deterministic portfolio tests without external assets."""
    identity_demos = [([[0, 1], [1, 0]], [[0, 1], [1, 0]])]
    first, second = build_task_solvers(identity_demos)
    assert first([[0, 1], [1, 0]]) == [[0, 1], [1, 0]]
    assert callable(second)

    rotation_demos = [
        ([[1, 0, 0], [0, 2, 0]], [[0, 1], [2, 0], [0, 0]]),
        ([[0, 3], [4, 0], [0, 0]], [[0, 4, 0], [0, 0, 3]]),
    ]
    rotate_solver, _ = build_task_solvers(rotation_demos)
    assert rotate_solver([[1, 0, 0], [0, 2, 0]]) == [[0, 1], [2, 0], [0, 0]]

    color_demos = [
        ([[1, 0], [2, 1]], [[3, 0], [4, 3]]),
        ([[2, 2], [0, 1]], [[4, 4], [0, 3]]),
    ]
    color_solver, _ = build_task_solvers(color_demos)
    assert color_solver([[1, 2], [0, 1]]) == [[3, 4], [0, 3]]

    crop_demos = [
        ([[0, 0, 0], [0, 2, 0], [0, 0, 0]], [[2]]),
        ([[0, 0, 0, 0], [0, 3, 3, 0], [0, 0, 0, 0]], [[3, 3]]),
    ]
    crop_solver, _ = build_task_solvers(crop_demos)
    assert crop_solver([[0, 0, 0], [0, 2, 0], [0, 0, 0]]) == [[2]]

    symmetry_demos = [
        (
            [[1, 0, 0, 0, 0], [0, 0, 0, 0, 0]],
            [[1, 0, 0, 0, 1], [0, 0, 0, 0, 0]],
        ),
        (
            [[0, 2, 0, 0, 0], [0, 0, 0, 0, 0]],
            [[0, 2, 0, 2, 0], [0, 0, 0, 0, 0]],
        ),
    ]
    symmetry_solver, _ = build_task_solvers(symmetry_demos)
    assert symmetry_solver.family == "symmetry_completion"
    assert symmetry_solver.program == "complete_vertical"
    assert symmetry_solver(symmetry_demos[0][0]) == symmetry_demos[0][1]

    recolor_object_demos = [
        (
            [[1, 1, 0, 1], [0, 0, 0, 0]],
            [[2, 2, 0, 1], [0, 0, 0, 0]],
        ),
        (
            [[1, 1, 1, 0, 1], [0, 0, 0, 0, 0]],
            [[2, 2, 2, 0, 1], [0, 0, 0, 0, 0]],
        ),
    ]
    object_solver, _ = build_task_solvers(recolor_object_demos)
    assert object_solver.family == "object_recolor"
    assert object_solver.program == "recolor_largest_2"
    assert object_solver(recolor_object_demos[0][0]) == recolor_object_demos[0][1]

    object_filter_demos = [
        (
            [[1, 1, 0, 1], [0, 0, 0, 0]],
            [[1, 1, 0, 0], [0, 0, 0, 0]],
        ),
        (
            [[1, 1, 1, 0, 1], [0, 0, 0, 0, 0]],
            [[1, 1, 1, 0, 0], [0, 0, 0, 0, 0]],
        ),
    ]
    object_filter_solver, _ = build_task_solvers(object_filter_demos)
    assert object_filter_solver.family == "object_filter"
    assert object_filter_solver.program == "keep_largest_object"
    assert object_filter_solver(object_filter_demos[0][0]) == object_filter_demos[0][1]

    copy_demos = [
        (
            [[1, 0, 0], [0, 0, 0], [0, 0, 0]],
            [[1, 0, 0], [0, 1, 0], [0, 0, 0]],
        ),
        (
            [[0, 2, 0], [0, 0, 0], [0, 0, 0]],
            [[0, 2, 0], [0, 0, 2], [0, 0, 0]],
        ),
    ]
    copy_solver, _ = build_task_solvers(copy_demos)
    assert copy_solver(copy_demos[0][0]) == copy_demos[0][1]


def main() -> None:
    """Run self-tests, or print the selected families for a JSON demo file."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--demos")
    args = parser.parse_args()
    if args.self_test or args.demos is None:
        run_self_tests()
        print("arc_agi_enumerative_solver self-tests: PASS")
        return
    import json
    with open(args.demos, encoding="utf-8") as stream:
        raw = json.load(stream)
    solvers = build_task_solvers(raw)
    print(json.dumps([getattr(solver, "program", solver.__name__) for solver in solvers]))


if __name__ == "__main__":
    main()
