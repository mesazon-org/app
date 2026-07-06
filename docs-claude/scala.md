# Scala

## Coding Standards

### Naming rules

__Recommended Name Convention__

- {{ Ownner }} {{ Entity }} {{ Optional }} {{ Behavior }}
- The name should be descriptive and indicate the purpose of the variable, method, class, etc.
- Ownner examples: User, Organization, Contact, Order etc
- Entity examples: Details, Otp, Name, Email
- Optional examples: Empty or Opt
- Behavior examples: Update

##### General Scala naming rules

##### 1. values, defs, parameters

- Should use `camelCase`
- ✅ `userDetailsRowOpt`, `otpNew`, `userOtpRowUpdated`, `uploadOrganizationLogo`
- ❌ `user_details_row_opt`, `OtpNew`

##### 2. classes, traits, objects, enums, type aliases, constants

- Should use `PascalCase`
- ✅ `UserSignUpService`, `UserOtpRepository`, `UserOtpRow`, `UserOtpQueries`, `UserID`, `MaxDimensionPixels`
- ❌ `userSignUpService`, `userOtpRepository`, `userOtpRow`, `userOtpQueries`, `userID`, `maxDimensionPixels`

#### Iron new type

##### 1. Naming Convention for Iron New Types

- Should use `PascalCase` and be named after the entity they represent, suffixed with `ID`
- Should be used to represent unique identifiers for entities in the system
- Should be used to avoid confusion with other types and to provide type safety
- Should always use {{ Ownner }} {{ Entity }}
- ✅ `UserID`, `PhoneRegion`, `OrganizationLogoOriginalFileName`, `PhoneNumberE164`, `UpdatedAt`
- ❌ `userID`, `organizationId`, `contactId`, `id`

#### Repository naming rules

##### 1. Repository classes

- Should be named after the entity they manage, suffixed with `Repository`
- ✅ `UserOtpRepository`, `OrganizationRepository`
- ❌ `UserOtpQueries`, `UserOtpDao`

##### 2. Repository methods

- Should be prefixed with `get`, `insert`, `create`, `upsert`, `is` , `update`, `delete`, `getAndIncrease` etc.
  depending on the operation
- Should use the entity name in the method name
- Should use plural form for methods that return multiple entities
- Should use suffix `byUserID` when multiple methods to get the same entity exist, but with different parameters
- ✅ `getUserOtpByUserID`, `insertUserOtp`, `updateUserOtp`, `deleteUserOtp`, `getAllUserOtps`,
  `isOrganizationSlugExists`, `createOrganization`
- ❌ `insert`, `update`, `delete`, `getAll`, `getByUserID`

##### 3. Repository method parameters

- Should always use recommended name convention for parameters with `ID`.
- Should always use recommended name convention for enum parameters.
- Should ignore in some cases the {{ Owner }} prefix when repository is already named after the entity they manage.
- Should include Opt when the parameter is optional.
- Should include behavior when the parameter is used to perform a specific action.
- ✅ `userID`, `organizationID`, `addressLine1OptUpdate`, `organizationStageOptUpdate`, `phoneNumber`
- ❌ `userId`, `organizationId`, `id`, `addressLine1UpdateOpt`, `organizationStageUpdateOpt`, `stageOptUpdate`

##### 4. Repository domain models
- Should be named after the entity they represent, suffixed with `Row`
- ✅ `UserOtpRow`, `OrganizationRow`
- ❌ `UserOtp`, `Organization`

##### 5. Repository domain model parameters
- Should always use recommended name convention for parameters with `ID`.
- Should always use recommended name convention for enum parameters.
- Should follow similar principle with parameters in repository methods, where the {{ Owner }} prefix can be ignored in some cases when the domain is already named after the entity they manage.
- Should include Opt when the parameter is optional.
- ✅ `userID`, `organizationID`, `addressLine1Opt`, `organizationStage`, `phoneNumber`
- ❌ `userId`, `organizationId`, `id`, 

##### 6. Repository query classes
- Should be named after the table they manage, suffixed with `Queries`
- ✅ `UserOtpQueries`, `OrganizationQueries`
- ❌ `UserOtpRepository`, `OrganizationRepository`

##### 7. Repository query class methods
- Should be named as operations with `get`, `insert`, `create`, `upsert`, `is` , `update`, `delete`, `getAndIncrease` etc.
- Should not use the entity name in the method name, as the query class is already named after the entity they manage
- Should use plural form for methods that return multiple entities
- Should use suffix `byUserID` when multiple methods to get the same entity exist, but with different parameters
- ✅ `getByUserID`, `insert`, `update`, `delete`, `getAll`,
  `isOrganizationSlugExists`
- ❌ `getUserOtpByUserID`, `insertUserOtp`, `updateUserOtp`, `deleteUserOtp`, `getAllUserOtps`, `isOrganizationSlugExists`,

##### 8. Repository query class methods testing
- Should be named as operations with `get`, `insert`, `create`, `upsert`, `is` , `update`, `delete`, `getAndIncrease` etc.
- Should not use the entity name in the method name, as the query class is already named after the entity they manage
- Should use plural form for methods that return multiple entities
- Should add pre-suffix if returning all entities with `All`
- SHould add suffix `Testing` to indicate that the method is used for testing purposes
- Should use suffix `byUserID` when multiple methods to get the same entity exist, but with different parameters
- ✅ `getAllTesting`, `deleteTesting`, `insertTesting`, `updateTesting`, `getByUserIDTesting`
- ❌ `getUserOtpByUserID`, `insertUserOtp`, `updateUserOtp`, `deleteUserOtp`, `getAllUserOtps`, `isOrganizationSlugExists`,