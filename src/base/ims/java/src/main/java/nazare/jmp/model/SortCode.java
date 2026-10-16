package nazare.jmp.model;

/** Bank sort code, from copybook {@code SORTCODE} ({@code 77 SORTCODE PIC 9(6) VALUE 987654}). */
public final class SortCode {

	public static final String VALUE = "987654";

	public static final int LENGTH = 6;

	private SortCode() {
	}

	public static boolean isValid(String sortcode) {
		return sortcode != null && sortcode.length() == LENGTH && sortcode.chars().allMatch(Character::isDigit);
	}
}
