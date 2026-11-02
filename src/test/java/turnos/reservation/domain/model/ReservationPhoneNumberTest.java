package turnos.reservation.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationPhoneNumberTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "+54 9 261 555-1234 | +5492615551234",
            "(261) 555.1234     | 2615551234",
            "1234567            | 1234567",
            "+1234567           | +1234567",
            "123456789012345    | 123456789012345",
            "+123456789012345   | +123456789012345",
            "+ 1-2.3(4)5 6 7    | +1234567"
    })
    void aValidPhoneIsNormalizedWithoutItsSeparators(String phoneNumber, String normalized) {
        assertThat(ReservationPhoneNumber.parse(phoneNumber)).get()
                .extracting(ReservationPhoneNumber::value).isEqualTo(normalized);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "---",
            "123456",
            "+123456",
            "1234567890123456",
            "+1234567890123456",
            "261555abcd",
            "54+92615551234",
            "++5492615551234",
            "261\t5551234",
            "2615551234\n",
            "٢٦١٥٥٥١٢٣٤",
            "261/555/1234",
            "261_555_1234"
    })
    void anythingElseIsNotAPhone(String phoneNumber) {
        assertThat(ReservationPhoneNumber.parse(phoneNumber)).isEmpty();
    }

    @Test
    void itsTextDoesNotShowTheNumber() {
        ReservationPhoneNumber phone = ReservationPhoneNumber.parse("+5492615551234").orElseThrow();

        assertThat(phone.toString()).doesNotContain("5492615551234");
    }
}
