from fastapi import APIRouter, Depends

from .. import tasks
from ..models import ProvisionTask
from ..session_provision import provision_session

router = APIRouter()


@router.post("/v1/tasks/provision-session")
def provision_session_task(body: ProvisionTask, caller=Depends(tasks.tasks_caller)):
    """Cloud Tasks callback: open the Drive upload targets for one session.

    Authenticated by the OIDC token Cloud Tasks attaches (see tasks.tasks_caller)
    — not a user route. Cloud Tasks retries on a non-2xx, and provision_session
    is idempotent, so a retry resumes rather than duplicating work.
    """
    return provision_session(body.sessionId)
