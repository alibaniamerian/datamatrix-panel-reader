"""Conventional ECC200 dimensions (rows, columns); DMRE is outside this scope."""
SQUARE_SIZES=(10,12,14,16,18,20,22,24,26,32,36,40,44,48,52,64,72,80,88,96,104,120,132,144)
RECTANGLES=((8,18),(8,32),(12,26),(12,36),(16,36),(16,48))
SYMBOL_SHAPES=tuple((n,n) for n in SQUARE_SIZES)+RECTANGLES+tuple((c,r) for r,c in RECTANGLES)
