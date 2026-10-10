package com.pixelplus.owner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoStateTest {
    @Test fun companySwitchScopesRequestsAndAlerts() {
        val all = DemoSeed.state()
        val scoped = all.chooseCompany("demo-2")
        assertEquals(4, all.visibleRequests().size)
        assertEquals(1, scoped.visibleRequests().size)
        assertEquals(1, scoped.visibleAlerts().size)
        assertTrue(scoped.visibleRequests().all { it.companyId == "demo-2" })
    }

    @Test fun cannotChangeAnotherCompanyRequestOrAlert() {
        val scoped = DemoSeed.state().chooseCompany("demo-1")
        assertEquals(scoped, scoped.markRequest("REQ-103", RequestStatus.REVIEWED))
        assertEquals(scoped, scoped.markAlertRead("AL-2"))
    }

    @Test fun transitionIsOneWayAndIdempotent() {
        val start = DemoSeed.state()
        val reviewed = start.markRequest("REQ-101", RequestStatus.REVIEWED)
        assertEquals(RequestStatus.REVIEWED, reviewed.requests.first { it.id == "REQ-101" }.status)
        assertEquals(reviewed, reviewed.markRequest("REQ-101", RequestStatus.REJECTED))
        assertEquals(start, start.markRequest("missing", RequestStatus.REJECTED))
    }

    @Test fun demoCanNeverIssueRealCode() {
        val state = DemoSeed.state()
        assertFalse(state.realIssuanceEnabled)
        assertTrue(state.requests.all { it.deviceRef.startsWith("DEMO-") })
    }

    @Test fun invalidCompanyIsNotSelected() {
        val state = DemoSeed.state()
        assertEquals(state, state.chooseCompany("not-allowed"))
    }
}
