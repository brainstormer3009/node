/******************************************************************************
 * Copyright © 2013-2016 The Nxt Core Developers.                             *
 *                                                                            *
 * See the AUTHORS.txt, DEVELOPER-AGREEMENT.txt and LICENSE.txt files at      *
 * the top-level directory of this distribution for the individual copyright  *
 * holder information and the developer policies on copyright and licensing.  *
 *                                                                            *
 * Unless otherwise agreed in a custom licensing agreement, no part of the    *
 * Nxt software, including this file, may be copied, modified, propagated,    *
 * or distributed except according to the terms contained in the LICENSE.txt  *
 * file.                                                                      *
 *                                                                            *
 * Removal or modification of this copyright notice is prohibited.            *
 *                                                                            *
 ******************************************************************************/

package xin;

import xin.Account.ControlType;
import xin.XinException.AccountControlException;
import xin.VoteWeighting.VotingModel;
import xin.db.DbIterator;
import xin.db.DbKey;
import xin.db.DbUtils;
import xin.db.VersionedEntityDbTable;
import xin.util.Convert;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

public final class AccountRestrictions {

    public static final class PhasingOnly {

        public static PhasingOnly get(long accountId) {
            return phasingControlTable.get(phasingControlDbKeyFactory.newKey(accountId));
        }

        public static int getCount() {
            return phasingControlTable.getCount();
        }

        public static DbIterator<PhasingOnly> getAll(int from, int to) {
            return phasingControlTable.getAll(from, to);
        }

        static void set(Account senderAccount, Attachment.SetPhasingOnly attachment) {
            PhasingParams phasingParams = attachment.getPhasingParams();
            if (phasingParams.getVoteWeighting().getVotingModel() == VotingModel.NONE) {
                //no voting - remove the control
                senderAccount.removeControl(ControlType.PHASING_ONLY);
                PhasingOnly phasingOnly = get(senderAccount.getId());
                phasingControlTable.delete(phasingOnly);
            } else {
                senderAccount.addControl(ControlType.PHASING_ONLY);
                PhasingOnly phasingOnly = get(senderAccount.getId());
                if (phasingOnly == null) {
                    phasingOnly = new PhasingOnly(senderAccount.getId(), phasingParams, attachment.getMaxFees(),
                            attachment.getMinDuration(), attachment.getMaxDuration());
                } else {
                    phasingOnly.phasingParams = phasingParams;
                    phasingOnly.maxFees = attachment.getMaxFees();
                    phasingOnly.minDuration = attachment.getMinDuration();
                    phasingOnly.maxDuration = attachment.getMaxDuration();
                }
                phasingControlTable.insert(phasingOnly);
            }
        }

        private final DbKey dbKey;
        private final long accountId;
        private PhasingParams phasingParams;
        private long maxFees;
        private short minDuration;
        private short maxDuration;

        private PhasingOnly(long accountId, PhasingParams params, long maxFees, short minDuration, short maxDuration) {
            this.accountId = accountId;
            dbKey = phasingControlDbKeyFactory.newKey(this.accountId);
            phasingParams = params;
            this.maxFees = maxFees;
            this.minDuration = minDuration;
            this.maxDuration = maxDuration;
        }

        private PhasingOnly(ResultSet rs, DbKey dbKey) throws SQLException {
            this.accountId = rs.getLong("account_id");
            this.dbKey = dbKey;
            Long[] whitelist = DbUtils.getArray(rs, "whitelist", Long[].class);
            phasingParams = new PhasingParams(rs.getByte("voting_model"),
                    rs.getLong("holding_id"),
                    rs.getLong("quorum"),
                    rs.getLong("min_balance"),
                    rs.getByte("min_balance_model"),
                    whitelist == null ? Convert.EMPTY_LONG : Convert.toArray(whitelist));
            this.maxFees = rs.getLong("max_fees");
            this.minDuration = rs.getShort("min_duration");
            this.maxDuration = rs.getShort("max_duration");
        }

        public long getAccountId() {
            return accountId;
        }

        public PhasingParams getPhasingParams() {
            return phasingParams;
        }

        public long getMaxFees() {
            return maxFees;
        }

        public short getMinDuration() {
            return minDuration;
        }

        public short getMaxDuration() {
            return maxDuration;
        }

        private void checkTransaction(Transaction transaction, boolean validatingAtFinish) throws AccountControlException {
            if (!validatingAtFinish && maxFees > 0 && Math.addExact(transaction.getFeeTQT(), PhasingPoll.getSenderPhasedTransactionFees(transaction.getSenderId())) > maxFees) {
                throw new AccountControlException(String.format("Maximum total fees limit of %f token exceeded", ((double) maxFees) / Constants.ONE_XIN));
            }
            if (transaction.getType() == TransactionType.Messaging.PHASING_VOTE_CASTING) {
                return;
            }
            Appendix.Phasing phasingAppendix = transaction.getPhasing();
            if (phasingAppendix == null) {
                throw new AccountControlException("Non-multisig transaction when multisig account control is enabled");
            }
            if (!phasingParams.equals(phasingAppendix.getParams())) {
                throw new AccountControlException("Multisig parameters mismatch multisig account control. Expected: " +
                        phasingParams.toString() + " . Actual: " + phasingAppendix.getParams().toString());
            }
            if (!validatingAtFinish) {
                int duration = phasingAppendix.getFinishHeight() - Xin.getBlockchain().getHeight();
                if ((maxDuration > 0 && duration > maxDuration) || (minDuration > 0 && duration < minDuration)) {
                    throw new AccountControlException("Invalid multisig duration " + duration);
                }
            }
        }

        private void save(Connection con) throws SQLException {
            try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO account_control_phasing "
                    + "(account_id, whitelist, voting_model, quorum, min_balance, holding_id, min_balance_model, "
                    + "max_fees, min_duration, max_duration, height, latest) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)")) {
                int i = 0;
                pstmt.setLong(++i, this.accountId);
                DbUtils.setArrayEmptyToNull(pstmt, ++i, Convert.toArray(phasingParams.getWhitelist()));
                pstmt.setByte(++i, phasingParams.getVoteWeighting().getVotingModel().getCode());
                DbUtils.setLongZeroToNull(pstmt, ++i, phasingParams.getQuorum());
                DbUtils.setLongZeroToNull(pstmt, ++i, phasingParams.getVoteWeighting().getMinBalance());
                DbUtils.setLongZeroToNull(pstmt, ++i, phasingParams.getVoteWeighting().getHoldingId());
                pstmt.setByte(++i, phasingParams.getVoteWeighting().getMinBalanceModel().getCode());
                pstmt.setLong(++i, this.maxFees);
                pstmt.setShort(++i, this.minDuration);
                pstmt.setShort(++i, this.maxDuration);
                pstmt.setInt(++i, Xin.getBlockchain().getHeight());
                pstmt.executeUpdate();
            }
        }

    }

    private static final DbKey.LongKeyFactory<PhasingOnly> phasingControlDbKeyFactory = new DbKey.LongKeyFactory<PhasingOnly>("account_id") {
        @Override
        public DbKey newKey(PhasingOnly rule) {
            return rule.dbKey;
        }
    };

    private static final VersionedEntityDbTable<PhasingOnly> phasingControlTable = new VersionedEntityDbTable<PhasingOnly>("account_control_phasing", phasingControlDbKeyFactory) {

        @Override
        protected PhasingOnly load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
            return new PhasingOnly(rs, dbKey);
        }

        @Override
        protected void save(Connection con, PhasingOnly phasingOnly) throws SQLException {
            phasingOnly.save(con);
        }
    };

    static void init() {
    }

    static void checkTransaction(Transaction transaction, boolean validatingAtFinish) throws XinException.NotCurrentlyValidException {
        Account senderAccount = Account.getAccount(transaction.getSenderId());
        if (senderAccount == null) {
            throw new XinException.NotCurrentlyValidException("Account " + Long.toUnsignedString(transaction.getSenderId()) + " does not exist yet");
        }
        if (senderAccount.getControls().contains(Account.ControlType.PHASING_ONLY)) {
            PhasingOnly phasingOnly = PhasingOnly.get(transaction.getSenderId());
            phasingOnly.checkTransaction(transaction, validatingAtFinish);
        }
    }

    static boolean isBlockDuplicate(Transaction transaction, Map<TransactionType, Map<String, Integer>> duplicates) {
        Account senderAccount = Account.getAccount(transaction.getSenderId());
        if (!senderAccount.getControls().contains(Account.ControlType.PHASING_ONLY)) {
            return false;
        }
        if (PhasingOnly.get(transaction.getSenderId()).getMaxFees() == 0) {
            return false;
        }
        return transaction.getType() != TransactionType.AccountControl.SET_PHASING_ONLY &&
                TransactionType.isDuplicate(TransactionType.AccountControl.SET_PHASING_ONLY, Long.toUnsignedString(senderAccount.getId()),
                        duplicates, true);
    }

}
