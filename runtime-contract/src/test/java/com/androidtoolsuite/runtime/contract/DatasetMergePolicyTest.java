package com.androidtoolsuite.runtime.contract;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DatasetMergePolicyTest {
    @Test public void rulesRequireObjectsAndBooleanFlagsWithoutCoercion() throws Exception {
        for (String rule : new String[]{"{\"kind\":\"object\",\"fields\":[]}",
                "{\"kind\":\"object\",\"constants\":false}",
                "{\"kind\":\"array\",\"keys\":[\"id\"],\"descending\":\"true\"}",
                "{\"kind\":\"array\",\"keys\":[\"id\"],\"aliases\":[]}"}) {
            JSONObject policy = new JSONObject().put("format", "json").put("rule", new JSONObject(rule));
            assertThrows(ContractException.class, () -> DatasetMergePolicy.validate(policy, "json"));
        }
    }
    @Test public void validatorsAndArchivePathsMustMatchTheDeclaredPolicy() throws Exception {
        JSONObject json = new JSONObject("{\"format\":\"json\",\"rule\":{\"kind\":\"object\"}}");
        assertEquals(json.toString(), DatasetMergePolicy.validate(json, "json").toString());
        assertThrows(ContractException.class, () -> DatasetMergePolicy.validate(json, "zip"));
        JSONObject zip = new JSONObject("{\"format\":\"zip\",\"entries\":[{\"pattern\":\"../outside\",\"rule\":{\"kind\":\"current\"}}]}");
        assertThrows(ContractException.class, () -> DatasetMergePolicy.validate(zip, "zip"));
    }
}
