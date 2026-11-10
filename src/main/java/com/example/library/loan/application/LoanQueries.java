package com.example.library.loan.application;

import com.example.library.infrastructure.error.BusinessException;
import com.example.library.infrastructure.error.ErrorCode;
import com.example.library.infrastructure.web.PageQuery;
import com.example.library.infrastructure.web.SlicePage;
import com.example.library.loan.model.LoanScope;
import com.example.library.loan.persistence.LoanViewMapper;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class LoanQueries {
    private final LoanViewMapper mapper;
    private final Clock clock;

    public LoanQueries(LoanViewMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public LoanView require(long id) {
        Instant now = clock.instant();
        LoanView view = mapper.selectById(id);
        if (view == null) {
            throw new BusinessException(ErrorCode.LOAN_NOT_FOUND);
        }
        fillOverdue(view, now);
        return view;
    }

    public SlicePage<LoanView> search(Long accountId, Long bookId, LoanScope scope, PageQuery page) {
        Instant now = clock.instant();
        List<LoanView> rows = mapper.search(accountId, bookId, scope, now, page.getFetchSize(), page.getOffset());
        for (LoanView view : rows) {
            fillOverdue(view, now);
        }
        return SlicePage.from(rows, page);
    }

    private void fillOverdue(LoanView view, Instant now) {
        view.setOverdue(view.getReturnedAt() == null && !now.isBefore(view.getDueAt()));
    }
}
