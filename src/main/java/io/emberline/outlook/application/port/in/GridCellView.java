package io.emberline.outlook.application.port.in;

/** One cell of {@code GridOccurrence.cells}: all fields ({@code fields=full}) or the seven map fields ({@code fields=map}). */
public sealed interface GridCellView permits GridCellFull, GridCellMap {
}
