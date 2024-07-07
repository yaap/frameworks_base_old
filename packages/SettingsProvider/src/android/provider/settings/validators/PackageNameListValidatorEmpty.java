/*
 * Copyright (C) 2024 Yet Another AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.provider.settings.validators;

import static android.provider.settings.validators.SettingsValidators.PACKAGE_NAME_VALIDATOR;

/**
 * Validate a list of package names.
 * Allows an empty item.
 * 
 * @hide
 */
final class PackageNameListValidatorEmpty extends ListValidator {
    PackageNameListValidatorEmpty(String separator) {
        super(separator);
    }

    @Override
    protected boolean isEntryValid(String entry) {
        return entry != null;
    }

    @Override
    protected boolean isItemValid(String item) {
        if (item == "") {
            return true;
        }
        return PACKAGE_NAME_VALIDATOR.validate(item);
    }
}
