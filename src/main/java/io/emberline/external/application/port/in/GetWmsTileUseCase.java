package io.emberline.external.application.port.in;

public interface GetWmsTileUseCase {

    /** PNG bytes of the tile (from redis-cache or FIRMS). */
    byte[] tile(WmsTileQuery query);
}
