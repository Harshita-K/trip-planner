-- Verification codes now live in Redis (auto-expiring, atomic Lua checks); see OtpStore.
drop table email_verifications;
