-- YB note: LOAD is not ported because The extension is loaded using cmd line flag.
CREATE USER regress_user1;
-- YB Note: In passwordcheck_extra, the default length is 15. However in passwordcheck there was
-- no check for max length. Increase max length to accomodate the tests in this file.
SET passwordcheck.maximum_length TO 30;

-- ok
-- YB Note: The test has changed from upstream because passwordcheck_extra expects
-- an upper case and a number by default.
ALTER USER regress_user1 PASSWORD 'a_N1ce_long_password';

-- error: too short
ALTER USER regress_user1 PASSWORD 'tooshrt';

-- error: contains user name
ALTER USER regress_user1 PASSWORD 'xyzregress_user1';

-- error: contains only letters
ALTER USER regress_user1 PASSWORD 'alessnicelongpassword';

-- encrypted ok (password is "secret")
ALTER USER regress_user1 PASSWORD 'md51a44d829a20a23eac686d9f0d258af13';

-- error: password is user name
ALTER USER regress_user1 PASSWORD 'md5e589150ae7d28f93333afae92b36ef48';

DROP USER regress_user1;
