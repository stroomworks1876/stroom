package stroom.receive.common;

interface DataFeedKeyHasher {

    String hash(String dataFeedKey);

//        default boolean verify(String apiKeyStr, String hash) {
//            final String computedHash = hash(Objects.requireNonNull(apiKeyStr));
//            return Objects.equals(Objects.requireNonNull(hash), computedHash);
//        }

    DataFeedKeyHashAlgorithm getAlgorithm();
}
