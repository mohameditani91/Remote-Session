import Testing
@testable import SideSeat

@Suite struct ApprovalTests {

    @Test func onlyRecentAgentInputInControlCountsAsAnAgentAction() {
        var approval = AgentApproval()
        #expect(!approval.isAgentAction(inControl: true, at: 100))

        approval.agentInput(at: 100)
        #expect(approval.isAgentAction(inControl: true, at: 100 + AgentApproval.window - 0.1))
        #expect(!approval.isAgentAction(inControl: true, at: 100 + AgentApproval.window))
        #expect(!approval.isAgentAction(inControl: false, at: 101))
    }

    @Test func anAgentInputThatDidNothingDoesNotCount() {
        var approval = AgentApproval()
        let earlier = approval.agentInput(at: 100)
        approval.agentInputDidNothing(restoring: earlier)
        #expect(!approval.isAgentAction(inControl: true, at: 101))

        approval.agentInput(at: 200)
        let before = approval.agentInput(at: 250)
        approval.agentInputDidNothing(restoring: before)
        #expect(approval.isAgentAction(inControl: true, at: 200 + AgentApproval.window - 0.1))
        #expect(!approval.isAgentAction(inControl: true, at: 200 + AgentApproval.window))
    }

    @Test func actionsAskedWhileACardIsShowingShareItsAnswer() {
        var approval = AgentApproval()
        var answers: [String: Bool] = [:]
        #expect(approval.ask("Delete the task") { answers["first"] = $0 })
        #expect(!approval.ask("Delete the task") { answers["second"] = $0 })
        #expect(approval.action == "Delete the task")

        approval.answer().forEach { $0(false) }
        #expect(answers == ["first": false, "second": false])
        #expect(!approval.isPending)
    }

    @Test func theUsersOwnTouchEndsTheAgentWindow() {
        var approval = AgentApproval()
        approval.agentInput(at: 100)
        approval.userInput()
        #expect(!approval.isAgentAction(inControl: true, at: 101))
    }

    @Test func anAnswerUsesUpTheAgentsInput() {
        var approval = AgentApproval()
        approval.agentInput(at: 100)
        _ = approval.ask("Pay") { _ in }
        approval.answer().forEach { $0(true) }
        #expect(!approval.isAgentAction(inControl: true, at: 101))
    }

    @Test func theAgentSeesTheQuestionAndTheAnswer() {
        #expect(OutgoingMessage.approval(action: "Pay", allowed: nil).json == #"{"action":"Pay","type":"approval"}"#)
        #expect(OutgoingMessage.approval(action: "Pay", allowed: false).json == #"{"action":"Pay","allowed":false,"type":"approval"}"#)
    }
}
