package com.yesbee.nwtrack.storage;

import com.yesbee.nwtrack.shared.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class StorageTokenPolicyTest {
    private final StorageTokenPolicy policy = new StorageTokenPolicy();
    @Test void tokenIsNotRequiredWhenFeatureIsDisabled(){assertThat(policy.normalize(false,null)).isNull();}
    @Test void tokenIsNormalizedWhenEnabled(){assertThat(policy.normalize(true,"  rack-12 ")).isEqualTo("RACK-12");}
    @Test void blankTokenIsRejectedWhenEnabled(){assertThatThrownBy(() -> policy.normalize(true," ")).isInstanceOf(BusinessRuleException.class);}
}
