#include "config_json.h"
#include <cassert>
#include <cstring>
#include <iostream>

int main() {
    ConfigJson spaced("{ \"wm\": 1, \"ws\": \"existing-wifi\", \"wp\": \"quoted\\\"password\", \"me\": 1, \"mr\": 3, \"mh\": \"systemdeveloper.ddns.net\", \"mp\": 8883 }");
    assert(spaced.valid()); assert(spaced.fieldTypesValid());
    assert(spaced.number("wm", 0) == 1);
    assert(std::strcmp(spaced.string("ws"), "existing-wifi") == 0);
    assert(std::strcmp(spaced.string("wp"), "quoted\"password") == 0);
    ConfigJson partial("{\"mh\":\"systemdeveloper.ddns.net\",\"mp\":8883}");
    assert(partial.valid()); assert(partial.fieldTypesValid());
    assert(!partial.has("wm")); assert(!partial.has("ws")); assert(!partial.has("wp"));
    assert(partial.number("wm", 1) == 1);
    ConfigJson invalid("{ invalid }"); assert(!invalid.valid());
    ConfigJson array("[]"); assert(!array.valid());
    ConfigJson wrongString("{\"ws\":123}"); assert(!wrongString.fieldTypesValid());
    ConfigJson wrongPort("{\"mp\":0}"); assert(!wrongPort.fieldTypesValid());
    ConfigJson wrongProtocol("{\"mr\":4}"); assert(!wrongProtocol.fieldTypesValid());
    ConfigJson wrongMode("{\"wm\":2}"); assert(!wrongMode.fieldTypesValid());
    ConfigJson wrongInteger("{\"mp\":\"8883\"}"); assert(!wrongInteger.fieldTypesValid());
    std::cout << "Configuration JSON whitespace, escapes, partial update and validation tests passed\n";
}
