package io.emberline.hotspots.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Point layer of a request: {@code reference} reads reference/, {@code all} reads reference/ and other/. */
public enum Layer {

    REFERENCE("reference", List.of("reference")),
    ALL("all", List.of("reference", "other"));

    private static final Map<String, Layer> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(Layer::code, Function.identity()));

    private final String code;
    private final List<String> folders;

    Layer(String code, List<String> folders) {
        this.code = code;
        this.folders = folders;
    }

    public String code() {
        return code;
    }

    /** Sub-folders of harmonized_hotspots/ this layer reads. */
    public List<String> folders() {
        return folders;
    }

    public static Optional<Layer> ofCode(String code) {
        return Optional.ofNullable(code == null ? null : BY_CODE.get(code));
    }
}
