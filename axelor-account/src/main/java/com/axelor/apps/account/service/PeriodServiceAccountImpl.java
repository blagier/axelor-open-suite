/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2024 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.account.service;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.AdjustHistoryService;
import com.axelor.apps.base.service.PeriodServiceImpl;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.User;
import com.axelor.db.Query;
import com.axelor.exception.AxelorException;
import com.google.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.collections.CollectionUtils;

@Singleton
public class PeriodServiceAccountImpl extends PeriodServiceImpl implements PeriodServiceAccount {

  protected MoveValidateService moveValidateService;
  protected MoveRepository moveRepository;
  protected AccountConfigService accountConfigService;

  @Inject
  public PeriodServiceAccountImpl(
      PeriodRepository periodRepo,
      AdjustHistoryService adjustHistoryService,
      MoveValidateService moveValidateService,
      MoveRepository moveRepository,
      AccountConfigService accountConfigService) {
    super(periodRepo, adjustHistoryService);
    this.moveValidateService = moveValidateService;
    this.moveRepository = moveRepository;
    this.accountConfigService = accountConfigService;
  }

  public void close(Period period) throws AxelorException {

    if (period.getYear().getTypeSelect() == YearRepository.TYPE_FISCAL) {
      moveValidateService.accountingMultiple(getMoveListToValidateQuery(period));
      period = periodRepo.find(period.getId());
    }
    super.close(period);
  }

  public Query<Move> getMoveListToValidateQuery(Period period) {
    return moveRepository
        .all()
        .filter(
            "self.period.id = ?1 AND (self.statusSelect NOT IN (?2,?3, ?4) OR (self.statusSelect = ?2 AND (self.archived = false OR self.archived is null)))",
            period.getId(),
            MoveRepository.STATUS_NEW,
            MoveRepository.STATUS_ACCOUNTED,
            MoveRepository.STATUS_CANCELED)
        .order("date")
        .order("id");
  }

  public boolean isManageClosedPeriod(Period period, User user) throws AxelorException {
    if (period != null && period.getYear().getCompany() != null && user.getGroup() != null) {
      AccountConfig accountConfig =
          accountConfigService.getAccountConfig(period.getYear().getCompany());
      if (CollectionUtils.isEmpty(accountConfig.getClosureAuthorizedRoleList())) {
        return false;
      }
      for (Role role : accountConfig.getClosureAuthorizedRoleList()) {
        if (user.getGroup().getRoles().contains(role) || user.getRoles().contains(role)) {
          return true;
        }
      }
    }
    return false;
  }

  public boolean isTemporarilyClosurePeriodManage(Period period, User user) throws AxelorException {
    if (period != null && period.getYear().getCompany() != null && user.getGroup() != null) {
      AccountConfig accountConfig =
          accountConfigService.getAccountConfig(period.getYear().getCompany());
      if (CollectionUtils.isEmpty(accountConfig.getTemporaryClosureAuthorizedRoleList())) {
        return false;
      }
      for (Role role : accountConfig.getTemporaryClosureAuthorizedRoleList()) {
        if (user.getGroup().getRoles().contains(role) || user.getRoles().contains(role)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public boolean isAuthorizedToAccountOnPeriod(Period period, User user) throws AxelorException {
    if (period != null && period.getYear().getCompany() != null && user.getGroup() != null) {
      if (period.getStatusSelect() == PeriodRepository.STATUS_CLOSED) {
        return false;
      }
      if (period.getStatusSelect() == PeriodRepository.STATUS_TEMPORARILY_CLOSED) {
        AccountConfig accountConfig =
            accountConfigService.getAccountConfig(period.getYear().getCompany());
        for (Role role : accountConfig.getMoveOnTempClosureAuthorizedRoleList()) {
          if (user.getGroup().getRoles().contains(role) || user.getRoles().contains(role)) {
            return true;
          }
        }
        return false;
      }
      return true;
    }
    return false;
  }

  @Override
  public boolean isAuthorizedToAccountOnPeriod(Move move, User user) throws AxelorException {
    if (move.getFunctionalOriginSelect() == MoveRepository.FUNCTIONAL_ORIGIN_OPENING
        || move.getFunctionalOriginSelect() == MoveRepository.FUNCTIONAL_ORIGIN_CLOSURE) {
      return true;
    }
    return isAuthorizedToAccountOnPeriod(move.getPeriod(), user);
  }
}
