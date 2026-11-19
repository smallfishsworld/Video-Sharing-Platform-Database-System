package io.sustc.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.sustc.dto.RegisterUserReq.Gender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The pure helper methods of the services, without a database. */
class ServiceHelpersTest {

    @ParameterizedTest
    @ValueSource(strings = {"1月1日", "01月01日", "2月29日", "4月30日", "8月31日", "12月31日"})
    void realCalendarDaysAreValidBirthdays(String birthday) {
        assertThat(UserServiceImpl.isValidBirthday(birthday)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2月30日", "6月31日", "13月1日", "0月10日", "3月0日", "123月1日", "1月1", "1-1", "１月１日", "x1月1日"})
    void otherStringsAreNot(String birthday) {
        assertThat(UserServiceImpl.isValidBirthday(birthday)).isFalse();
    }

    @Test
    void theBirthdayIsOptional() {
        assertThat(UserServiceImpl.isValidBirthday(null)).isTrue();
        assertThat(UserServiceImpl.isValidBirthday("")).isTrue();
    }

    @Test
    void gendersAreStoredWithTheLabelsOfTheImportedData() {
        assertThat(UserServiceImpl.storedSex(Gender.MALE)).isEqualTo("男");
        assertThat(UserServiceImpl.storedSex(Gender.FEMALE)).isEqualTo("女");
        assertThat(UserServiceImpl.storedSex(Gender.UNKNOWN)).isEqualTo("保密");
    }

    @Test
    void keywordsAreSplitOnSpacesWithoutEmptyWords() {
        assertThat(VideoServiceImpl.splitKeywords("sustech database")).containsExactly("sustech", "database");
        assertThat(VideoServiceImpl.splitKeywords("  a   b ")).containsExactly("a", "b");
        assertThat(VideoServiceImpl.splitKeywords("   ")).isEmpty();
        // only spaces separate keywords
        assertThat(VideoServiceImpl.splitKeywords("a\tb")).containsExactly("a\tb");
    }
}
